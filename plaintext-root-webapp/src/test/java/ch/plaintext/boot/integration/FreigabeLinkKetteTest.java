/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.testsupport.EmbeddedPg;
import ch.plaintext.apitoken.JwtTokenService;
import ch.plaintext.apitoken.McpBearerTokenFilter;
import ch.plaintext.McpUserRoles;
import ch.plaintext.freigabe.FreigabeInhalt;
import ch.plaintext.freigabe.FreigabeQuelle;
import ch.plaintext.freigabe.FreigabeRecht;
import ch.plaintext.freigabe.FreigabeZugriff;
import ch.plaintext.freigabe.link.entity.FreigabeLink;
import ch.plaintext.freigabe.link.repository.FreigabeLinkRepository;
import ch.plaintext.freigabe.link.service.FreigabeLinkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1476: Freigabe-Links durch die echte Kette (Security, CSRF, Rate-Limit, Controller, Service, Flyway-Schema
 * auf PostgreSQL) mit einer Test-{@link FreigabeQuelle}. Positiv- und Negativproben je Eigenschaft: Hash, Ablauf,
 * Widerruf, r gegen rw, Teil, GET ändert nichts, Token nie am /mcp.
 *
 * <p>{@code @DirtiesContext} wie {@code StartseitenSchleifeChainTest}: ein eigener Kontext (Profil) hielte sonst seinen
 * Verbindungspool bis zum Ende der JVM offen, und die CI-Datenbank antwortet der nächsten Testklasse mit „too many
 * clients" (Woodpecker root #494, {@code FlywayMigrationTest}).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "karte1476"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FreigabeLinkKetteTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "freigabelinkkettetest");
    }

    /** Eigenes Profil: der Komponentenscan von RootBootApplication erfasst auch Testklassen. */
    @Profile("karte1476")
    @Component
    static class TestQuelle implements FreigabeQuelle {

        static final List<String> AUFRUFE = new CopyOnWriteArrayList<>();

        @Override
        public String typ() {
            return "test1476";
        }

        @Override
        public boolean darfFreigeben(Long objektId) {
            return objektId < 100;
        }

        @Override
        public boolean schreibbar() {
            return true;
        }

        @Override
        public Optional<FreigabeInhalt> zeige(FreigabeZugriff z) {
            AUFRUFE.add("zeige " + z.objektId() + " " + z.teil() + " " + z.recht() + " anonym="
                    + (SecurityContextHolder.getContext().getAuthentication() == null));
            if (z.teil() != null && !z.teil().startsWith("seite-")) {
                return Optional.empty();
            }
            return Optional.of(FreigabeInhalt.html("<p>objekt " + z.objektId() + " teil " + z.teil() + " mandat " + z.mandat() + "</p>"));
        }

        @Override
        public Optional<FreigabeInhalt> schreibe(FreigabeZugriff z, String contentType, byte[] inhalt) {
            AUFRUFE.add("schreibe " + z.objektId() + " " + new String(inhalt, StandardCharsets.UTF_8));
            return Optional.of(new FreigabeInhalt("text/plain;charset=UTF-8", "gespeichert".getBytes(StandardCharsets.UTF_8),
                    null));
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private FreigabeLinkService service;
    @Autowired
    private FreigabeLinkRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenService jwtTokenService;
    @Autowired
    private IApiTokenService apiTokenService;
    @Autowired
    private McpUserRoles mcpUserRoles;

    @BeforeEach
    void anmelden() {
        TestQuelle.AUFRUFE.clear();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("karte1476", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("PROPERTY_MANDAT_demo"))));
    }

    @AfterEach
    void abmelden() {
        SecurityContextHolder.clearContext();
    }

    private String token(FreigabeLinkService.Link l) {
        return l.url().substring(l.url().lastIndexOf('/') + 1);
    }

    private ResponseEntity<String> ruf(HttpMethod methode, String token, String body) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        RestTemplate rest = new RestTemplate(factory);
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_PLAIN);
        return rest.exchange("http://localhost:" + port + FreigabeLinkService.PFAD + token, methode, new HttpEntity<>(body, h),
                String.class);
    }

    @Test
    @DisplayName("Lese-Link: 200 mit Inhalt, CSP sandbox, kein Referer/Cache; das Modul läuft anonym")
    void leseLink() {
        FreigabeLinkService.Link l = service.erzeuge("test1476", 7L, null, FreigabeRecht.LESEN, 1, "Test");
        ResponseEntity<String> r = ruf(HttpMethod.GET, token(l), null);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        assertTrue(r.getBody().contains("objekt 7 teil null mandat demo"), r.getBody());
        assertTrue(r.getHeaders().getFirst("Content-Security-Policy").contains("sandbox"));
        assertEquals("no-referrer", r.getHeaders().getFirst("Referrer-Policy"));
        assertTrue(r.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(List.of("zeige 7 null LESEN anonym=true"), TestQuelle.AUFRUFE);
    }

    @Test
    @DisplayName("In der DB steht nur der SHA-256, nirgends das Token")
    void nurHash() throws Exception {
        FreigabeLinkService.Link l = service.erzeuge("test1476", 8L, null, FreigabeRecht.LESEN, 1, null);
        String t = token(l);
        FreigabeLink zeile = repository.findById(l.id()).orElseThrow();
        String erwartet = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8)));
        assertEquals(erwartet, zeile.getTokenHash());
        String alles = jdbc.queryForList("SELECT * FROM freigabe_link WHERE id = ?", l.id()).toString();
        assertFalse(alles.contains(t), alles);
    }

    @Test
    @DisplayName("Falsches oder kaputtes Token: 404, das Modul wird nicht gefragt")
    void falschesToken() {
        service.erzeuge("test1476", 9L, null, FreigabeRecht.LESEN, 1, null);
        assertEquals(404, ruf(HttpMethod.GET, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", null).getStatusCode().value());
        assertEquals(404, ruf(HttpMethod.GET, "eyJhbGciOiJSUzI1NiJ9.e30.x", null).getStatusCode().value());
        assertEquals(404, ruf(HttpMethod.POST, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "x").getStatusCode().value());
        assertTrue(TestQuelle.AUFRUFE.isEmpty());
    }

    @Test
    @DisplayName("Abgelaufen: 404 (Gegenprobe vorher 200)")
    void abgelaufen() {
        FreigabeLinkService.Link l = service.erzeuge("test1476", 10L, null, FreigabeRecht.LESEN, 1, null);
        assertEquals(200, ruf(HttpMethod.GET, token(l), null).getStatusCode().value());
        FreigabeLink zeile = repository.findById(l.id()).orElseThrow();
        zeile.setGueltigBis(LocalDate.now().minusDays(1));
        repository.save(zeile);
        assertEquals(404, ruf(HttpMethod.GET, token(l), null).getStatusCode().value());
    }

    @Test
    @DisplayName("Widerrufen: 404 (Gegenprobe vorher 200)")
    void widerrufen() {
        FreigabeLinkService.Link l = service.erzeuge("test1476", 11L, null, FreigabeRecht.SCHREIBEN, 1, null);
        assertEquals(200, ruf(HttpMethod.GET, token(l), null).getStatusCode().value());
        service.widerrufe(l.id());
        assertEquals(404, ruf(HttpMethod.GET, token(l), null).getStatusCode().value());
        assertEquals(404, ruf(HttpMethod.POST, token(l), "neu").getStatusCode().value());
        assertTrue(service.liste("test1476", 11L).isEmpty());
    }

    @Test
    @DisplayName("POST mit r-Link: 403, das Modul schreibt nicht; mit rw-Link: 200, ohne CSRF-Token")
    void rGegenRw() {
        FreigabeLinkService.Link r = service.erzeuge("test1476", 12L, null, FreigabeRecht.LESEN, 1, null);
        FreigabeLinkService.Link rw = service.erzeuge("test1476", 12L, null, FreigabeRecht.SCHREIBEN, 1, null);
        assertEquals(403, ruf(HttpMethod.POST, token(r), "boese").getStatusCode().value());
        assertTrue(TestQuelle.AUFRUFE.isEmpty(), TestQuelle.AUFRUFE.toString());
        ResponseEntity<String> ok = ruf(HttpMethod.POST, token(rw), "neu");
        assertEquals(200, ok.getStatusCode().value(), ok.getBody());
        assertEquals("gespeichert", ok.getBody());
        assertEquals(List.of("schreibe 12 neu"), TestQuelle.AUFRUFE);
    }

    @Test
    @DisplayName("GET auf einen rw-Link ändert nichts: nur zeige, nie schreibe")
    void getAendertNichts() {
        FreigabeLinkService.Link rw = service.erzeuge("test1476", 13L, null, FreigabeRecht.SCHREIBEN, 1, null);
        ruf(HttpMethod.GET, token(rw), "neu");
        ruf(HttpMethod.HEAD, token(rw), null);
        assertTrue(TestQuelle.AUFRUFE.stream().allMatch(a -> a.startsWith("zeige ")), TestQuelle.AUFRUFE.toString());
    }

    @Test
    @DisplayName("Teil-Link: der Teil kommt aus dem Link, ein unbekannter Teil gibt 404")
    void teilLink() {
        FreigabeLinkService.Link l = service.erzeuge("test1476", 14L, "seite-2", FreigabeRecht.LESEN, 1, null);
        assertFalse(l.url().contains("seite-2"), "der Teil steht nicht in der Adresse");
        ResponseEntity<String> r = ruf(HttpMethod.GET, token(l), null);
        assertTrue(r.getBody().contains("teil seite-2"), r.getBody());
        FreigabeLinkService.Link falsch = service.erzeuge("test1476", 14L, "abschnitt-x", FreigabeRecht.LESEN, 1, null);
        assertEquals(404, ruf(HttpMethod.GET, token(falsch), null).getStatusCode().value());
    }

    @Test
    @DisplayName("Aufrufzähler: zählt gelieferte Aufrufe, nicht 404")
    void zaehler() {
        FreigabeLinkService.Link l = service.erzeuge("test1476", 15L, null, FreigabeRecht.LESEN, 0, null);
        ruf(HttpMethod.GET, token(l), null);
        ruf(HttpMethod.GET, token(l), null);
        FreigabeLink zeile = repository.findById(l.id()).orElseThrow();
        assertEquals(2, zeile.getAufrufe());
        assertNotNull(zeile.getZuletztAufgerufen());
        assertNull(zeile.getGueltigBis(), "0 Tage = unbefristet");
    }

    @Test
    @DisplayName("Fremdes Objekt: kein Link (darfFreigeben nein)")
    void fremdesObjekt() {
        org.junit.jupiter.api.Assertions.assertThrows(java.util.NoSuchElementException.class,
                () -> service.erzeuge("test1476", 999L, null, FreigabeRecht.LESEN, 1, null));
    }

    @Test
    @DisplayName("Token nie am /mcp: Bearer-Filter (JWT und DATABASE) weist es ab; Gegenprobe echter JWT kommt durch")
    void nieAmMcp() throws Exception {
        String t = token(service.erzeuge("test1476", 16L, null, FreigabeRecht.SCHREIBEN, 1, null));
        for (McpBearerTokenFilter filter : List.of(McpBearerTokenFilter.jwtOnly(jwtTokenService, mcpUserRoles, jti -> false),
                McpBearerTokenFilter.withRevocationCheck(apiTokenService, mcpUserRoles, jti -> false))) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
            req.addHeader("Authorization", "Bearer " + t);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(req, res, chain);
            assertEquals(401, res.getStatus());
            assertNull(chain.getRequest(), "die Kette darf nicht weiterlaufen");
        }
        // Gegenprobe: ohne sie bewiese die Schleife oben nur, dass der Filter alles abweist.
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer " + jwtTokenService.generateToken(1L, "demo", "karte1476@plaintext.ch", "karte1476", 1, "READ"));
        MockFilterChain chain = new MockFilterChain();
        McpBearerTokenFilter.jwtOnly(jwtTokenService, mcpUserRoles, jti -> false).doFilter(req, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest(), "ein echter JWT muss durchkommen");
    }
}

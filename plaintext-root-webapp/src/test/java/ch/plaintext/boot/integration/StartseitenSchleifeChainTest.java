/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.testsupport.EmbeddedPg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 1331 — STOERUNG 23.09.2026: after the login Daniel was locked out by a redirect loop
 * {@code / -> /Index.html -> / -> ...}. His start page was {@code Index.html} (capital I); it passed
 * the form check of {@code StartpageResolver}, the page does not exist, and
 * {@code PlaintextErrorViewResolver} turned the 404 of a browser request into a redirect to
 * {@code /} — which sent the browser to the missing page again.
 *
 * <p><b>Why through the real chain:</b> the loop needs three parts at once — the login success
 * handler, {@code /} ({@code Index}) and the ERROR dispatch with the HTML error view. MockMvc
 * performs no ERROR dispatch (see {@link ErrorDispatchChainTest}); only a real Tomcat shows the
 * 302 that closed the loop.</p>
 *
 * <p>Positive control: an individual start page that exists ({@code access-denied.html}, a real
 * root page) is still used after the login and by {@code /}.</p>
 *
 * <p><b>Why {@code @DirtiesContext}:</b> every cached Spring context keeps its
 * connection pool open until the JVM ends. On the first CI run of this test (Woodpecker root #331)
 * the one additional context was enough for the CI PostgreSQL to answer the NEXT test class
 * ({@code FlywayMigrationTest}) with "FATAL: sorry, too many clients already". This class needs
 * its context exactly once, so it closes it afterwards. (A smaller pool is no way out: with
 * {@code maximum-pool-size=2} the context does not even start — Flyway waits 30 s for a connection.)</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StartseitenSchleifeChainTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "startseitenschleifechaintest");
    }

    private static final String PASSWORT = "startseite-test-passwort";

    @LocalServerPort
    private int port;

    @Autowired
    private MyUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private RestClient client() {
        java.net.http.HttpClient jdkClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        return RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(jdkClient))
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    @ParameterizedTest(name = "Startseite {0} -> index.html")
    @ValueSource(strings = {"Index.html", "gibtesnicht.html"})
    @DisplayName("Nicht existierende Startseite: Login und / fuehren auf index.html, nicht in eine Schleife")
    void nichtExistierendeStartseite_faelltAufIndexZurueck(String startseite) {
        String session = login(benutzer(startseite));

        String wurzel = location(get("/", session, MediaType.TEXT_HTML_VALUE));
        assertTrue(wurzel.endsWith("/index.html") || wurzel.equals("index.html"),
                "/ muss auf index.html leiten, nicht auf " + startseite + " — war: " + wurzel);
    }

    @Test
    @DisplayName("Nicht existierende Seite im Browser: keine Umleitung zurueck auf / (der Schleifenschluss)")
    void nichtExistierendeSeite_leitetNichtAufWurzel() {
        String session = login(benutzer("Index.html"));

        ResponseEntity<String> seite = get("/Index.html", session, MediaType.TEXT_HTML_VALUE);
        String ziel = seite.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotEquals("/", pfad(ziel), "Ein 404 darf nicht auf / umleiten — / fuehrt zur Startseite zurueck");
        if (seite.getStatusCode().is3xxRedirection()) {
            assertTrue(pfad(ziel).equals("/index.html"),
                    "Wenn umgeleitet wird, dann auf die feste Seite /index.html — war: " + ziel);
        } else {
            assertEquals(404, seite.getStatusCode().value());
        }
    }

    @Test
    @DisplayName("Ohne Accept: text/html bleibt es beim 404 (so sah der Testbenutzer per curl den Fehler)")
    void ohneHtmlAccept_bleibt404() {
        String session = login(benutzer("Index.html"));

        ResponseEntity<String> seite = get("/Index.html", session, MediaType.APPLICATION_JSON_VALUE);
        assertEquals(404, seite.getStatusCode().value());
    }

    @Test
    @DisplayName("Positivkontrolle: eine existierende individuelle Startseite bleibt")
    void existierendeStartseite_bleibt() {
        String benutzer = benutzer("access-denied.html");
        String session = login(benutzer);

        String wurzel = location(get("/", session, MediaType.TEXT_HTML_VALUE));
        assertTrue(wurzel.endsWith("access-denied.html"),
                "Eine existierende Startseite muss weiter benutzt werden — war: " + wurzel);
    }

    // ---------------------------------------------------------------------------------------

    /** Creates (or resets) a user with the given start page and returns the login name. */
    private String benutzer(String startseite) {
        String name = "startseite-" + startseite.replaceAll("[^A-Za-z]", "").toLowerCase() + "@example.com";
        MyUserEntity user = userRepository.findByUsername(name);
        if (user == null) {
            user = new MyUserEntity();
            user.setUsername(name);
            user.setMandat("default");
            user.addRole("user");
        }
        user.setPassword(passwordEncoder.encode(PASSWORT));
        user.setStartpage(startseite);
        userRepository.save(user);
        return name;
    }

    private String login(String benutzer) {
        ResponseEntity<String> loginSeite = client().get().uri("/login.xhtml").retrieve().toEntity(String.class);
        String session = sessionCookie(loginSeite, null);
        String csrf = csrf(loginSeite.getBody());
        ResponseEntity<String> login = client().post()
                .uri("/login")
                .header(HttpHeaders.COOKIE, session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("username=" + benutzer + "&password=" + PASSWORT + "&_csrf=" + csrf)
                .retrieve().toEntity(String.class);
        assertTrue(login.getStatusCode().is3xxRedirection(), "Login muss umleiten, war: " + login.getStatusCode());
        String ziel = location(login);
        assertTrue(!ziel.contains("/login"), "Login muss gelingen, war: " + ziel);
        return sessionCookie(login, session);
    }

    private ResponseEntity<String> get(String pfad, String session, String accept) {
        return client().get().uri(pfad)
                .header(HttpHeaders.COOKIE, session)
                .header(HttpHeaders.ACCEPT, accept)
                .retrieve().toEntity(String.class);
    }

    private static String location(ResponseEntity<String> response) {
        assertTrue(response.getStatusCode().is3xxRedirection(), "Umleitung erwartet, war: " + response.getStatusCode());
        String ziel = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(ziel);
        return ziel;
    }

    /** Path part of a Location header (absolute or relative). */
    private static String pfad(String location) {
        if (location == null) {
            return "";
        }
        return location.replaceFirst("^https?://[^/]+", "");
    }

    private static String sessionCookie(ResponseEntity<String> response, String fallback) {
        for (String setCookie : response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
            if (setCookie.startsWith("JSESSIONID=")) {
                return setCookie.split(";", 2)[0];
            }
        }
        return fallback;
    }

    private static String csrf(String html) {
        assertNotNull(html);
        Matcher m = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("value=\"([^\"]+)\"\\s+name=\"_csrf\"").matcher(html);
        assertTrue(m.find(), "CSRF-Token nicht in HTML gefunden");
        return m.group(1);
    }
}

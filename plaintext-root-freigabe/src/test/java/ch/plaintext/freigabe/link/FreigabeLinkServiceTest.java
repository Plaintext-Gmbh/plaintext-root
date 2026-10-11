/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe.link;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.framework.EigeneAdresse;
import ch.plaintext.freigabe.FreigabeInhalt;
import ch.plaintext.freigabe.FreigabeQuelle;
import ch.plaintext.freigabe.FreigabeRecht;
import ch.plaintext.freigabe.FreigabeZugriff;
import ch.plaintext.freigabe.link.entity.FreigabeLink;
import ch.plaintext.freigabe.link.mcp.FreigabeLinkMcpTools;
import ch.plaintext.freigabe.link.repository.FreigabeLinkRepository;
import ch.plaintext.freigabe.link.service.FreigabeLinkService;
import ch.plaintext.freigabe.link.web.FreigabeLinkController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Karte 1476: Logik der Freigabe-Links ohne Spring, Repository als Liste im Speicher. */
class FreigabeLinkServiceTest {

    private final List<FreigabeLink> zeilen = new ArrayList<>();
    private final List<String> aufrufe = new ArrayList<>();
    private FreigabeLinkService service;
    private boolean schreibbar = true;

    private final FreigabeQuelle quelle = new FreigabeQuelle() {
        @Override
        public String typ() {
            return "t";
        }

        @Override
        public boolean darfFreigeben(Long objektId) {
            return objektId != 666L;
        }

        @Override
        public boolean schreibbar() {
            return schreibbar;
        }

        @Override
        public Optional<FreigabeInhalt> zeige(FreigabeZugriff z) {
            aufrufe.add("zeige " + z.teil() + " anonym=" + (SecurityContextHolder.getContext().getAuthentication() == null));
            if ("kaputt".equals(z.teil())) {
                throw new IllegalStateException("kaputt");
            }
            return Optional.of(FreigabeInhalt.html("<p>" + z.objektId() + "</p>"));
        }

        @Override
        public Optional<FreigabeInhalt> schreibe(FreigabeZugriff z, String contentType, byte[] inhalt) {
            aufrufe.add("schreibe " + new String(inhalt, StandardCharsets.UTF_8));
            return Optional.of(new FreigabeInhalt("text/plain", "ok".getBytes(StandardCharsets.UTF_8), "default-src 'none'"));
        }
    };

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        FreigabeLinkRepository repo = mock(FreigabeLinkRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            FreigabeLink l = a.getArgument(0);
            if (l.getId() == null) {
                l.setId((long) zeilen.size() + 1);
                zeilen.add(l);
            }
            return l;
        });
        when(repo.findByTokenHashAndDeletedFalse(anyString())).thenAnswer(a -> zeilen.stream()
                .filter(l -> l.getTokenHash().equals(a.getArgument(0)) && !l.getDeleted()).findFirst());
        when(repo.findByMandatAndDeletedFalseOrderByIdDesc(anyString())).thenAnswer(a -> zeilen.stream()
                .filter(l -> l.getMandat().equals(a.getArgument(0)) && !l.getDeleted()).toList());
        when(repo.findByIdAndMandatAndDeletedFalse(anyLong(), anyString())).thenAnswer(a -> zeilen.stream()
                .filter(l -> l.getId().equals(a.getArgument(0)) && l.getMandat().equals(a.getArgument(1)) && !l.getDeleted())
                .findFirst());
        ObjectProvider<FreigabeQuelle> quellen = mock(ObjectProvider.class);
        when(quellen.orderedStream()).thenAnswer(a -> Stream.of(quelle));
        PlaintextSecurity security = mock(PlaintextSecurity.class);
        when(security.getMandat()).thenReturn("demo");
        ObjectProvider<EigeneAdresse> adresse = mock(ObjectProvider.class);
        service = new FreigabeLinkService(repo, quellen, security, adresse);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", null, "ROLE_USER"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String token(FreigabeLinkService.Link l) {
        return l.url().substring(l.url().lastIndexOf('/') + 1);
    }

    @Test
    @DisplayName("Token: 32 Zeichen Base64url, kein Punkt (kein JWT), in der DB nur der Hash, Adresse nur beim Anlegen")
    void token() {
        FreigabeLinkService.Link l = service.erzeuge("t", 1L, " ", null, null, " Zweck ");
        String t = token(l);
        assertTrue(t.matches("[A-Za-z0-9_-]{32}"), t);
        assertTrue(l.url().startsWith("https://app.plaintext.ch/nosec/freigabe/"));
        FreigabeLink z = zeilen.get(0);
        assertEquals(64, z.getTokenHash().length());
        assertFalse(z.getTokenHash().contains(t));
        assertNull(z.getTeil());
        assertEquals("r", z.getRecht());
        assertEquals("Zweck", z.getZweck());
        assertEquals(LocalDate.now().plusDays(FreigabeLinkService.STANDARD_TAGE), z.getGueltigBis());
        assertNull(service.liste(null, null).get(0).url(), "die Liste zeigt nie eine Adresse");
        assertTrue(!token(service.erzeuge("t", 1L, null, null, 1, null)).equals(t), "jedes Token neu");
    }

    @Test
    @DisplayName("Anlegen verweigert: fremdes Objekt, unbekannter Typ, rw ohne Schreibfähigkeit, Tage und Teil ausserhalb")
    void anlegenVerweigert() {
        assertThrows(NoSuchElementException.class, () -> service.erzeuge("t", 666L, null, FreigabeRecht.LESEN, 1, null));
        assertThrows(NoSuchElementException.class, () -> service.erzeuge("x", 1L, null, FreigabeRecht.LESEN, 1, null));
        assertThrows(NoSuchElementException.class, () -> service.erzeuge("t", null, null, FreigabeRecht.LESEN, 1, null));
        assertThrows(IllegalArgumentException.class, () -> service.erzeuge("t", 1L, null, FreigabeRecht.LESEN, -1, null));
        assertThrows(IllegalArgumentException.class, () -> service.erzeuge("t", 1L, null, FreigabeRecht.LESEN, 3651, null));
        assertThrows(IllegalArgumentException.class, () -> service.erzeuge("t", 1L, "x".repeat(201), FreigabeRecht.LESEN, 1, null));
        schreibbar = false;
        assertThrows(IllegalArgumentException.class, () -> service.erzeuge("t", 1L, null, FreigabeRecht.SCHREIBEN, 1, null));
        assertTrue(zeilen.isEmpty());
    }

    @Test
    @DisplayName("Öffnen: richtig 200 und anonym, falsch/abgelaufen/widerrufen leer; Zähler nur bei Antwort")
    void oeffnen() {
        FreigabeLinkService.Link l = service.erzeuge("t", 1L, "seite-1", FreigabeRecht.LESEN, 1, null);
        assertTrue(service.zeige(token(l)).isPresent());
        assertEquals(List.of("zeige seite-1 anonym=true"), aufrufe);
        assertTrue(SecurityContextHolder.getContext().getAuthentication() != null, "die Anmeldung kommt danach zurück");
        assertEquals(1, zeilen.get(0).getAufrufe());

        assertTrue(service.zeige("A".repeat(32)).isEmpty());
        assertTrue(service.zeige(null).isEmpty());
        assertTrue(service.zeige("a.b.c").isEmpty());

        zeilen.get(0).setGueltigBis(LocalDate.now().minusDays(1));
        assertTrue(service.zeige(token(l)).isEmpty());
        zeilen.get(0).setGueltigBis(LocalDate.now());
        assertTrue(service.zeige(token(l)).isPresent(), "der letzte Tag gilt noch");

        service.widerrufe(l.id());
        assertTrue(service.zeige(token(l)).isEmpty());
        assertEquals(2, zeilen.get(0).getAufrufe());
    }

    @Test
    @DisplayName("Modul wirft: leer statt 500, kein Zähler")
    void modulWirft() {
        FreigabeLinkService.Link l = service.erzeuge("t", 1L, "kaputt", FreigabeRecht.LESEN, 1, null);
        assertTrue(service.zeige(token(l)).isEmpty());
        assertEquals(0, zeilen.get(0).getAufrufe());
    }

    @Test
    @DisplayName("Schreiben: r-Link verweigert, rw-Link schreibt")
    void schreiben() {
        FreigabeLinkService.Link r = service.erzeuge("t", 1L, null, FreigabeRecht.LESEN, 1, null);
        FreigabeLinkService.Link rw = service.erzeuge("t", 1L, null, FreigabeRecht.SCHREIBEN, 1, null);
        assertThrows(FreigabeLinkService.NurLesen.class, () -> service.schreibe(token(r), "text/plain", new byte[0]));
        assertTrue(aufrufe.isEmpty());
        assertTrue(service.schreibe(token(rw), "text/plain", "neu".getBytes(StandardCharsets.UTF_8)).isPresent());
        assertEquals(List.of("schreibe neu"), aufrufe);
        assertTrue(service.schreibe("A".repeat(32), null, new byte[0]).isEmpty());
    }

    @Test
    @DisplayName("Liste und Widerruf nur für Objekte, die der Benutzer freigeben darf")
    void listeUndWiderruf() {
        service.erzeuge("t", 1L, null, FreigabeRecht.LESEN, 1, null);
        FreigabeLink fremd = new FreigabeLink();
        fremd.setMandat("demo");
        fremd.setTyp("t");
        fremd.setObjektId(666L);
        fremd.setRecht("r");
        fremd.setTokenHash("x");
        fremd.setId(99L);
        zeilen.add(fremd);
        assertEquals(1, service.liste(null, null).size());
        assertEquals(1, service.liste("t", 1L).size());
        assertEquals(0, service.liste("t", 2L).size());
        assertThrows(NoSuchElementException.class, () -> service.widerrufe(99L));
        assertFalse(fremd.getDeleted());
    }

    @Test
    @DisplayName("Controller: 200 mit CSP des Moduls, 404 mit strenger CSP, 403 für r beim POST, 413 bei zu grossem Inhalt")
    void controller() throws Exception {
        FreigabeLinkController c = new FreigabeLinkController(service, 4);
        FreigabeLinkService.Link r = service.erzeuge("t", 1L, null, FreigabeRecht.LESEN, 1, null);
        FreigabeLinkService.Link rw = service.erzeuge("t", 1L, null, FreigabeRecht.SCHREIBEN, 1, null);
        var ok = c.zeige(token(r));
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(FreigabeInhalt.STRENG, ok.getHeaders().getFirst("Content-Security-Policy"));
        assertEquals("no-referrer", ok.getHeaders().getFirst("Referrer-Policy"));
        var nf = c.zeige("A".repeat(32));
        assertEquals(404, nf.getStatusCode().value());
        assertTrue(nf.getHeaders().getFirst("Content-Security-Policy").endsWith("sandbox"));

        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/");
        post.setContent("neu".getBytes(StandardCharsets.UTF_8));
        assertEquals(403, c.schreibe(token(r), post).getStatusCode().value());
        post = new MockHttpServletRequest("POST", "/");
        post.setContent("neu".getBytes(StandardCharsets.UTF_8));
        var geschrieben = c.schreibe(token(rw), post);
        assertEquals(200, geschrieben.getStatusCode().value());
        assertEquals("default-src 'none'", geschrieben.getHeaders().getFirst("Content-Security-Policy"));
        post = new MockHttpServletRequest("POST", "/");
        post.setContent("zu gross".getBytes(StandardCharsets.UTF_8));
        assertEquals(413, c.schreibe(token(rw), post).getStatusCode().value());
    }

    @Test
    @DisplayName("MCP: anlegen gibt die Adresse einmal, Liste ohne Token, Widerruf, Fehler als Text")
    void mcp() {
        FreigabeLinkMcpTools m = new FreigabeLinkMcpTools(service);
        String neu = m.erstelleFreigabeLink("t", 1L, "rw", "seite-2", 0, "Kunde");
        assertTrue(neu.startsWith("OK: Link 1 (rw, gültig bis unbefristet)"), neu);
        String t = neu.substring(neu.lastIndexOf('/') + 1);
        String liste = m.listFreigabeLinks(null, null);
        assertTrue(liste.contains("Typen: t") && liste.contains("teil=seite-2") && liste.contains("zweck=Kunde"), liste);
        assertFalse(liste.contains(t));
        assertTrue(m.erstelleFreigabeLink("t", 666L, null, null, null, null).startsWith("FEHLER"));
        assertTrue(m.erstelleFreigabeLink("t", 1L, "x", null, null, null).startsWith("FEHLER"));
        assertEquals("OK: Link 1 widerrufen.", m.widerrufeFreigabeLink(1L));
        assertTrue(m.widerrufeFreigabeLink(1L).startsWith("FEHLER"));
        assertEquals("Keine Freigabe-Links.", m.listFreigabeLinks("t", null));
    }

    @Test
    @DisplayName("Recht: r und rw, sonst Fehler")
    void recht() {
        assertSame(FreigabeRecht.LESEN, FreigabeRecht.von("r"));
        assertSame(FreigabeRecht.SCHREIBEN, FreigabeRecht.von(" RW "));
        assertThrows(IllegalArgumentException.class, () -> FreigabeRecht.von("w"));
        assertThrows(IllegalArgumentException.class, () -> FreigabeRecht.von(null));
    }
}

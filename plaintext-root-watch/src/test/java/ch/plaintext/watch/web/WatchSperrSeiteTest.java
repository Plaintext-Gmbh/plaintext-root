/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 1305: the answer a confined session gets — and whether the way out on it really works.
 *
 * <p>The one thing that can quietly break this page is the CSRF field. {@code /logout} is a
 * validated POST; a button without the field, or with the wrong parameter name, renders a page
 * that promises an exit and answers 403 when pressed. Spring Security hands the token out
 * either directly or behind a {@code Supplier}, depending on the request handler in force, so
 * both are measured here — and the counter-check is the page without any token at all, which
 * must not invent one.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchSperrSeiteTest {

    private static final CsrfToken TOKEN =
            new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "abc-123");

    private static MockHttpServletRequest browser() {
        MockHttpServletRequest anfrage = new MockHttpServletRequest("GET", "/index.html");
        anfrage.addHeader("Accept", "text/html,application/xhtml+xml");
        return anfrage;
    }

    private static String sperre(MockHttpServletRequest anfrage) throws Exception {
        MockHttpServletResponse antwort = new MockHttpServletResponse();
        WatchSperrSeite.nurDieUhr(anfrage, antwort);
        assertEquals(HttpServletResponse.SC_FORBIDDEN, antwort.getStatus(),
                "Diese Klasse aendert das Aussehen der Abweisung, nicht die Abweisung.");
        return antwort.getContentAsString();
    }

    @Test
    @DisplayName("Der Abmeldeknopf traegt das CSRF-Feld, sonst antwortet er selbst mit 403")
    void csrfFeldDirekt() throws Exception {
        MockHttpServletRequest anfrage = browser();
        anfrage.setAttribute(CsrfToken.class.getName(), TOKEN);

        String seite = sperre(anfrage);

        assertTrue(seite.contains("name=\"_csrf\""), seite);
        assertTrue(seite.contains("value=\"abc-123\""), seite);
    }

    @Test
    @DisplayName("Auch hinter einem Supplier wird der Token gefunden")
    void csrfFeldHinterSupplier() throws Exception {
        MockHttpServletRequest anfrage = browser();
        Supplier<CsrfToken> lieferant = () -> TOKEN;
        anfrage.setAttribute(CsrfToken.class.getName(), lieferant);

        assertTrue(sperre(anfrage).contains("value=\"abc-123\""),
                "Spring Security legt den Token je nach RequestHandler als Supplier ab.");
    }

    @Test
    @DisplayName("GEGENPROBE: ohne Token wird keiner erfunden")
    void ohneTokenKeinFeld() throws Exception {
        String seite = sperre(browser());

        assertFalse(seite.contains("name=\"_csrf\""),
                "Ein erfundenes Feld liesse den Knopf lautlos falsch aussenden: " + seite);
        assertTrue(seite.contains("action=\"/logout\""),
                "Der Knopf bleibt trotzdem stehen — er scheitert dann sichtbar, nicht heimlich.");
    }

    @Test
    @DisplayName("Der Kontextpfad steht vor jeder Adresse der Seite")
    void kontextpfadWirdVorangestellt() throws Exception {
        MockHttpServletRequest anfrage = browser();
        anfrage.setContextPath("/plaintext");

        String seite = sperre(anfrage);

        assertTrue(seite.contains("href=\"/plaintext/watch/start\""), seite);
        assertTrue(seite.contains("action=\"/plaintext/logout\""), seite);
    }

    @Test
    @DisplayName("Der Tokenwert wird maskiert in die Seite geschrieben")
    void tokenWirdMaskiert() throws Exception {
        MockHttpServletRequest anfrage = browser();
        anfrage.setAttribute(CsrfToken.class.getName(),
                new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "a\"><script>alert(1)</script>"));

        String seite = sperre(anfrage);

        assertFalse(seite.contains("<script>alert(1)</script>"),
                "Der Wert kommt aus einer Header-getriebenen Quelle und gehoert maskiert: " + seite);
        assertTrue(seite.contains("&lt;script&gt;"), seite);
    }

    @Test
    @DisplayName("Wer kein HTML annimmt, bekommt den Satz als Text")
    void ohneHtmlNurText() throws Exception {
        MockHttpServletRequest anfrage = new MockHttpServletRequest("GET", "/plaintext-layout/js/config.js");
        anfrage.addHeader("Accept", "*/*");
        MockHttpServletResponse antwort = new MockHttpServletResponse();

        WatchSperrSeite.nurDieUhr(anfrage, antwort);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, antwort.getStatus());
        assertEquals("text/plain;charset=UTF-8", antwort.getContentType());
        assertEquals(WatchSperrSeite.SATZ_NUR_DIE_UHR, antwort.getContentAsString());
    }

    @Test
    @DisplayName("Die Antwort wird nicht zwischengespeichert — sie gehoert zu EINER Sitzung")
    void keinZwischenspeicher() throws Exception {
        MockHttpServletResponse antwort = new MockHttpServletResponse();

        WatchSperrSeite.nurDieUhr(browser(), antwort);

        assertTrue(String.valueOf(antwort.getHeader("Cache-Control")).contains("no-store"),
                "Die Seite traegt ein CSRF-Token und gilt fuer genau eine Sitzung.");
    }
}

/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.apitoken.IApiTokenService.ApiTokenValidationResult;
import ch.plaintext.watch.service.WatchHandyLinkService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Card 1257: a token session is confined to the watch, and it dies with its token.
 *
 * <h2>The two questions this class answers</h2>
 *
 * <ol>
 *   <li><b>Where may it go?</b> A session opened by a phone link carries the owner's real roles
 *       — it has to, or the watch pages are empty. Without this filter it would therefore be
 *       the owner's whole session: a link handed out "just for the watch" would be a login.</li>
 *   <li><b>How long?</b> Only as long as the token is valid in {@code api_token}. Checking once
 *       at the exchange would leave an open session alive after a revocation until it times
 *       out — "generate a new one" would then not make the old phone stop.</li>
 * </ol>
 *
 * <p>Both counter-checks are here as well: an ordinary session must be untouched, and an intact
 * token must go through.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchTokenSitzungFilterTest {

    private static final String JWT = "ein.gueltiger.token";

    private IApiTokenService tokenDienst;
    private WatchTokenSitzungFilter filter;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> liefert(T wert) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(wert);
        return p;
    }

    @BeforeEach
    void setUp() {
        tokenDienst = mock(IApiTokenService.class);
        filter = new WatchTokenSitzungFilter(liefert(tokenDienst));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void tokenGueltig() {
        when(tokenDienst.validateToken(JWT)).thenReturn(Optional.of(
                new ApiTokenValidationResult(7L, "plaintext", "daniel@plaintext.ch",
                        WatchHandyLinkService.TOKEN_NAME, Instant.now().plusSeconds(3600), "READ")));
    }

    private void alsTokenSitzung() {
        SecurityContext k = SecurityContextHolder.createEmptyContext();
        k.setAuthentication(new UsernamePasswordAuthenticationToken("daniel@plaintext.ch", null,
                List.of(new SimpleGrantedAuthority(WatchTokenAnmeldeController.ROLLE_TOKEN_SITZUNG),
                        new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(k);
    }

    private void alsNormaleSitzung() {
        SecurityContext k = SecurityContextHolder.createEmptyContext();
        k.setAuthentication(new UsernamePasswordAuthenticationToken("daniel@plaintext.ch", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(k);
    }

    private record Lauf(MockHttpServletResponse antwort, MockFilterChain kette,
                        MockHttpServletRequest anfrage) {

        boolean durchgelassen() {
            return kette.getRequest() != null;
        }
    }

    private Lauf rufe(String pfad, boolean mitToken) throws Exception {
        return rufe(pfad, mitToken, null);
    }

    /** {@code accept} null heisst: kein Accept-Kopf, wie ihn ein Skript oder curl schickt. */
    private Lauf rufe(String pfad, boolean mitToken, String accept) throws Exception {
        MockHttpServletRequest anfrage = new MockHttpServletRequest("GET", pfad);
        if (mitToken) {
            anfrage.getSession(true).setAttribute(WatchTokenAnmeldeController.SITZUNG_TOKEN, JWT);
        }
        if (accept != null) {
            anfrage.addHeader("Accept", accept);
        }
        MockHttpServletResponse antwort = new MockHttpServletResponse();
        MockFilterChain kette = new MockFilterChain();
        filter.doFilter(anfrage, antwort, kette);
        return new Lauf(antwort, kette, anfrage);
    }

    /** Wie ein Telefon eine Seite aufruft. */
    private Lauf rufeAlsBrowser(String pfad) throws Exception {
        return rufe(pfad, true, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
    }

    @Test
    @DisplayName("Eine Token-Sitzung kommt auf die Watch-Seiten")
    void watchSeitenErlaubt() throws Exception {
        alsTokenSitzung();
        tokenGueltig();

        Lauf l = rufe("/watch/zeit.html", true);

        org.junit.jupiter.api.Assertions.assertTrue(l.durchgelassen());
        assertEquals(HttpServletResponse.SC_OK, l.antwort().getStatus());
    }

    @Test
    @DisplayName("Eine Token-Sitzung kommt NICHT auf eine beliebige andere Seite")
    void andereSeitenVerboten() throws Exception {
        alsTokenSitzung();
        tokenGueltig();

        Lauf l = rufe("/kontakte.html", true);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, l.antwort().getStatus());
        org.junit.jupiter.api.Assertions.assertFalse(l.durchgelassen(),
                "der Handy-Link darf nicht die volle Sitzung des Benutzers sein");
    }

    @Test
    @DisplayName("Auch die Watch-Einstellungen bleiben zu — ein Link verwaltet sich nicht selbst")
    void einstellungenVerboten() throws Exception {
        alsTokenSitzung();
        tokenGueltig();

        assertEquals(HttpServletResponse.SC_FORBIDDEN,
                rufe("/watch-einstellungen.html", true).antwort().getStatus());
    }

    @Test
    @DisplayName("GEGENPROBE: eine normale Sitzung wird von diesem Filter nicht angefasst")
    void normaleSitzungUnberuehrt() throws Exception {
        // Ohne diese Messung belegt der Test oben nichts — ein Filter, der JEDEN auf
        // /kontakte.html abweist, waere dort ebenfalls gruen und haette die Anwendung zerlegt.
        alsNormaleSitzung();

        Lauf l = rufe("/kontakte.html", false);

        org.junit.jupiter.api.Assertions.assertTrue(l.durchgelassen());
        assertEquals(HttpServletResponse.SC_OK, l.antwort().getStatus());
    }

    @Test
    @DisplayName("GEGENPROBE: ohne Anmeldung fasst der Filter ebenfalls nichts an")
    void ohneAnmeldungUnberuehrt() throws Exception {
        Lauf l = rufe("/kontakte.html", false);

        org.junit.jupiter.api.Assertions.assertTrue(l.durchgelassen());
    }

    @Test
    @DisplayName("Ein widerrufener Token beendet die laufende Sitzung beim naechsten Tippen")
    void widerrufBeendetDieSitzung() throws Exception {
        alsTokenSitzung();
        when(tokenDienst.validateToken(JWT)).thenReturn(Optional.empty());

        MockHttpServletRequest anfrage = new MockHttpServletRequest("GET", "/watch/zeit.html");
        var sitzung = anfrage.getSession(true);
        sitzung.setAttribute(WatchTokenAnmeldeController.SITZUNG_TOKEN, JWT);
        MockHttpServletResponse antwort = new MockHttpServletResponse();
        MockFilterChain kette = new MockFilterChain();

        filter.doFilter(anfrage, antwort, kette);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, antwort.getStatus());
        assertNull(kette.getRequest(), "die Seite darf nicht mehr gerendert werden");
        assertNull(anfrage.getSession(false),
                "die Sitzung selbst muss weg sein, nicht nur dieser eine Aufruf");
    }

    @Test
    @DisplayName("Ein Token, der inzwischen ein anderer Tokentyp ist, beendet die Sitzung ebenfalls")
    void falscherTokentypBeendetDieSitzung() throws Exception {
        alsTokenSitzung();
        when(tokenDienst.validateToken(JWT)).thenReturn(Optional.of(
                new ApiTokenValidationResult(7L, "plaintext", "daniel@plaintext.ch",
                        "Mein API-Token", Instant.now().plusSeconds(3600), "ADMIN")));

        assertEquals(HttpServletResponse.SC_FORBIDDEN, rufe("/watch/zeit.html", true).antwort().getStatus());
    }

    @Test
    @DisplayName("Eine Token-Sitzung ohne Token in der Sitzung wird beendet — fail-closed")
    void ohneTokenInDerSitzung() throws Exception {
        alsTokenSitzung();

        assertEquals(HttpServletResponse.SC_FORBIDDEN, rufe("/watch/zeit.html", false).antwort().getStatus());
    }

    @Test
    @DisplayName("Wirft die Pruefung, wird die Sitzung beendet statt durchgelassen")
    void pruefungWirft() throws Exception {
        alsTokenSitzung();
        when(tokenDienst.validateToken(JWT)).thenThrow(new IllegalStateException("DB weg"));

        assertEquals(HttpServletResponse.SC_FORBIDDEN, rufe("/watch/zeit.html", true).antwort().getStatus());
    }

    @Test
    @DisplayName("Der Einstiegspfad selbst wird nicht geprueft — dort entsteht die Sitzung erst")
    void einstiegAusgenommen() throws Exception {
        alsTokenSitzung();

        Lauf l = rufe(WatchHandyLinkService.EINSTIEGSPFAD, false);

        assertNotNull(l.kette().getRequest());
    }

    @Test
    @DisplayName("Die JSF-Ressourcen bleiben erreichbar, sonst kommt die Uhr ohne Stil")
    void ressourcenErlaubt() throws Exception {
        alsTokenSitzung();
        tokenGueltig();

        assertEquals(HttpServletResponse.SC_OK,
                rufe("/jakarta.faces.resource/watch.css.html", true).antwort().getStatus());
    }

    // ================================================================= Karte 1305: Antwort und Ausgang

    @Test
    @DisplayName("Abmelden ist erlaubt — eine Sitzung, die man nicht verlassen kann, ist eine Falle")
    void logoutErlaubt() throws Exception {
        alsTokenSitzung();
        tokenGueltig();

        Lauf l = rufe("/logout", true);

        org.junit.jupiter.api.Assertions.assertTrue(l.durchgelassen(),
                "Abmelden ist kein Verwalten: es widerruft den Link nicht, es beendet nur diese "
                        + "Sitzung. Ohne diesen Weg kommt man aus der Token-Sitzung nur noch "
                        + "heraus, indem man die Website-Daten im Browser loescht.");
        assertEquals(HttpServletResponse.SC_OK, l.antwort().getStatus());
    }

    @Test
    @DisplayName("GEGENPROBE: /logout gilt als ganzer Pfad, nicht als Praefix")
    void logoutIstKeinPraefix() throws Exception {
        // Sonst oeffnete der Ausgang eine ganze Adressfamilie: alles, was mit /logout anfaengt.
        alsTokenSitzung();
        tokenGueltig();

        Lauf l = rufe("/logout-alles.html", true);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, l.antwort().getStatus());
        org.junit.jupiter.api.Assertions.assertFalse(l.durchgelassen());
    }

    @Test
    @DisplayName("Die Abweisung erklaert sich und bietet den Ausgang an — keine Whitelabel-Seite")
    void abweisungIstLesbar() throws Exception {
        alsTokenSitzung();
        tokenGueltig();

        Lauf l = rufeAlsBrowser("/index.html");
        String seite = l.antwort().getContentAsString();

        // Die Schranke bleibt: Status und Nichtdurchlass sind unveraendert.
        assertEquals(HttpServletResponse.SC_FORBIDDEN, l.antwort().getStatus());
        org.junit.jupiter.api.Assertions.assertFalse(l.durchgelassen(),
                "Die Seite darf trotz lesbarer Antwort nicht geliefert werden.");

        // Neu ist nur, was auf dem Telefon steht.
        org.junit.jupiter.api.Assertions.assertTrue(
                l.antwort().getContentType().startsWith("text/html"),
                "Ein Browser bekommt eine Seite, kein Textschnipsel: " + l.antwort().getContentType());
        org.junit.jupiter.api.Assertions.assertTrue(seite.contains("wt-sperre"),
                "Die Sperrseite fehlt — dann liefert der Container wieder die Whitelabel-Seite: "
                        + seite);
        org.junit.jupiter.api.Assertions.assertTrue(seite.contains("/watch/start"),
                "Ohne den Weg zurueck zur Uhr ist die Seite eine Sackgasse: " + seite);
        org.junit.jupiter.api.Assertions.assertTrue(
                seite.contains("action=\"/logout\"") && seite.contains("method=\"post\""),
                "Der Ausgang fehlt: /logout wird als POST abgeschickt (PlaintextSecurityConfig). "
                        + seite);
    }

    @Test
    @DisplayName("GEGENPROBE: ein Skript bekommt keine Seite, sondern einen Satz")
    void ressourcenBekommenKeineSeite() throws Exception {
        // 20.09.2026 stand /plaintext-layout/js/config.js im Log — eine Ressource mitten in
        // einer halb geladenen Seite. Eine HTML-Seite als Antwort auf ein <script src> macht
        // daraus einen Parserfehler statt einer verschlossenen Tuer.
        alsTokenSitzung();
        tokenGueltig();

        Lauf l = rufe("/plaintext-layout/js/config.js", true, "*/*");

        assertEquals(HttpServletResponse.SC_FORBIDDEN, l.antwort().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                l.antwort().getContentType().startsWith("text/plain"),
                "erwartet text/plain, war " + l.antwort().getContentType());
        org.junit.jupiter.api.Assertions.assertFalse(
                l.antwort().getContentAsString().contains("<html"),
                "HTML an eine JavaScript-Anfrage: " + l.antwort().getContentAsString());
    }

    @Test
    @DisplayName("Auch der widerrufene Link antwortet lesbar, mit dem Weg zur Anmeldung")
    void widerrufAntwortetLesbar() throws Exception {
        alsTokenSitzung();
        when(tokenDienst.validateToken(JWT)).thenReturn(Optional.empty());

        Lauf l = rufeAlsBrowser("/watch/home.html");
        String seite = l.antwort().getContentAsString();

        assertEquals(HttpServletResponse.SC_FORBIDDEN, l.antwort().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(seite.contains("Dieser Link gilt nicht mehr."),
                "Dieselbe Formulierung wie am Einstieg — kein Orakel: " + seite);
        org.junit.jupiter.api.Assertions.assertTrue(seite.contains("/login.html"),
                "Die Sitzung ist beendet; was bleibt, ist die normale Anmeldung: " + seite);
        org.junit.jupiter.api.Assertions.assertFalse(seite.contains("/logout"),
                "Ein Abmeldeknopf auf einer schon beendeten Sitzung fuehrt ins Leere: " + seite);
    }
}

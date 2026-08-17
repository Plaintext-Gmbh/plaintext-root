/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.CookieTheftException;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

import jakarta.servlet.http.Cookie;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Karte 898: ein Series/Token-Mismatch darf keinen HTTP 500 erzeugen.
 *
 * <p>Der Test faehrt bewusst gegen die <b>echte</b> Spring-Klasse als Vergleich und nicht nur gegen
 * unsere Ableitung. Sonst belegt ein gruener Test nur, dass unsere Methode tut, was in ihr steht —
 * nicht, dass sie ein reales Verhalten aendert. {@link #spring_wirft_die_exception_nach_draussen()}
 * ist damit die Positivkontrolle: faellt sie weg, weil Spring das Verhalten aendert, ist unsere
 * Ableitung ueberfluessig geworden und dieser Test sagt es.
 */
class PlaintextRememberMeServicesTest {

    private static final String KEY = "test-key-fuer-karte-898";
    private static final String SERIE = "serie-abc";

    /** Repository, das eine Serie mit einem ANDEREN Token liefert — genau der Mismatch-Fall. */
    private static final class MismatchRepository implements PersistentTokenRepository {
        private final List<String> entfernt = new ArrayList<>();

        @Override public void createNewToken(PersistentRememberMeToken token) { /* nicht benoetigt */ }
        @Override public void updateToken(String series, String tokenValue, Date lastUsed) { /* nicht benoetigt */ }

        @Override
        public PersistentRememberMeToken getTokenForSeries(String seriesId) {
            return new PersistentRememberMeToken("mad", seriesId, "das-gespeicherte-token", new Date());
        }

        @Override
        public void removeUserTokens(String username) {
            entfernt.add(username);
        }
    }

    private static UserDetailsService nutzer() {
        return username -> (UserDetails) User.withUsername("mad").password("x")
                .authorities(AuthorityUtils.createAuthorityList("ROLE_USER")).build();
    }

    /** Cookie-Format von Spring Security: base64("serie:token"). */
    private static MockHttpServletRequest anfrageMitCookie(String serie, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login.html");
        String wert = Base64.getEncoder()
                .encodeToString((serie + ":" + token).getBytes(StandardCharsets.UTF_8));
        request.setCookies(new Cookie("remember-me", wert));
        return request;
    }

    @Test
    @DisplayName("POSITIVKONTROLLE: die Spring-Klasse wirft die CookieTheftException nach draussen")
    void spring_wirft_die_exception_nach_draussen() {
        PersistentTokenBasedRememberMeServices spring = new PersistentTokenBasedRememberMeServices(
                KEY, nutzer(), new MismatchRepository());

        assertThrows(CookieTheftException.class,
                () -> spring.autoLogin(anfrageMitCookie(SERIE, "mitgebrachtes-token"),
                        new MockHttpServletResponse()),
                "Wenn das nicht mehr wirft, ist PlaintextRememberMeServices ueberfluessig — "
                        + "genau dafuer steht dieser Test hier.");
    }

    @Test
    @DisplayName("unsere Ableitung liefert null statt zu werfen, und loescht das Cookie")
    void ableitung_liefert_null_und_loescht_das_cookie() {
        MismatchRepository repository = new MismatchRepository();
        PlaintextRememberMeServices unsere = new PlaintextRememberMeServices(KEY, nutzer(), repository);
        MockHttpServletResponse response = new MockHttpServletResponse();

        Authentication ergebnis = unsere.autoLogin(anfrageMitCookie(SERIE, "mitgebrachtes-token"), response);

        assertNull(ergebnis, "null heisst 'kein Auto-Login' — die Anfrage laeuft anonym weiter "
                + "und landet regulaer auf der Anmeldeseite");
        assertTrue(repository.entfernt.contains("mad"),
                "der Diebstahlschutz muss trotzdem greifen: alle Tokens des Benutzers sind weg");

        Cookie geloescht = response.getCookie("remember-me");
        assertNotNull(geloescht, "Spring loescht das Cookie per cancelCookie, bevor es wirft — "
                + "das darf unsere Ableitung nicht verschlucken");
        assertEquals(0, geloescht.getMaxAge(), "MaxAge 0 = Cookie loeschen");
    }

    @Test
    @DisplayName("NEGATIVPROBE: ein passendes Token wird weiterhin angemeldet")
    void passendes_token_wird_weiter_angemeldet() {
        PersistentTokenRepository passend = new PersistentTokenRepository() {
            @Override public void createNewToken(PersistentRememberMeToken token) { }
            @Override public void updateToken(String series, String tokenValue, Date lastUsed) { }
            @Override public PersistentRememberMeToken getTokenForSeries(String seriesId) {
                return new PersistentRememberMeToken("mad", seriesId, "gleiches-token", new Date());
            }
            @Override public void removeUserTokens(String username) {
                fail("bei passendem Token darf nichts entfernt werden");
            }
        };
        PlaintextRememberMeServices unsere = new PlaintextRememberMeServices(KEY, nutzer(), passend);

        Authentication ergebnis = unsere.autoLogin(
                anfrageMitCookie(SERIE, "gleiches-token"), new MockHttpServletResponse());

        assertNotNull(ergebnis, "der normale Remember-Me-Login muss unveraendert funktionieren");
        assertEquals("mad", ergebnis.getName());
    }

    @Test
    @DisplayName("ohne Cookie bleibt es beim bisherigen Verhalten: null, kein Fehler")
    void ohne_cookie_kein_fehler() {
        PlaintextRememberMeServices unsere =
                new PlaintextRememberMeServices(KEY, nutzer(), new MismatchRepository());

        assertNull(unsere.autoLogin(new MockHttpServletRequest("GET", "/login.html"),
                new MockHttpServletResponse()));
    }
}

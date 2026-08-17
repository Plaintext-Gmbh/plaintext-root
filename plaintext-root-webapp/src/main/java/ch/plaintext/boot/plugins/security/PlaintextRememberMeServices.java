/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.CookieTheftException;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

/**
 * Remember-Me-Dienst, der einen Series/Token-Mismatch nicht in einen HTTP 500 laufen laesst.
 *
 * <h2>Warum diese Klasse existiert (Karte 898)</h2>
 * Spring Security wirft bei einem Mismatch eine {@link CookieTheftException}, und zwar mit Absicht
 * bis nach draussen: {@code AbstractRememberMeServices#autoLogin} faengt sie, loescht das Cookie und
 * wirft sie <b>weiter</b>. Der {@code RememberMeAuthenticationFilter} umschliesst den
 * {@code autoLogin}-Aufruf nicht mit seinem {@code catch (AuthenticationException)} — die Exception
 * schlaegt deshalb bis in den {@code dispatcherServlet} durch, und der Benutzer bekommt auf
 * {@code /login.html} eine <b>500-Fehlerseite statt eines Anmeldeformulars</b>.
 *
 * <p>Gemessen im Zugriffsbericht (Karte 892): sechs solche 500er in sieben Tagen, jeder von einem
 * echten Browser, jeder auf der Anmeldeseite. Im Anwendungslog davor jeweils eine abgelaufene
 * Sitzung ({@code Ajax-Request abgewiesen (CSRF-Token fehlt oder ist ungueltig)}), danach die
 * Weiterleitung auf die Anmeldeseite — und dort der 500. Wer sich anmelden will, sieht also ein
 * kaputtes System.
 *
 * <h2>Warum das den Diebstahlschutz nicht aufweicht</h2>
 * Der Schutz greift, <b>bevor</b> die Exception fliegt, und liegt nicht in ihrer Weitergabe:
 * {@code PersistentTokenBasedRememberMeServices#processAutoLoginCookie} ruft
 * {@code tokenRepository.removeUserTokens(series)} und erst danach {@code throw}. Alle persistenten
 * Tokens des Benutzers sind zu diesem Zeitpunkt also schon verworfen, und {@code autoLogin} hat das
 * Cookie per {@code cancelCookie} geloescht. Diese Klasse aendert damit ausschliesslich die
 * <b>Antwort</b> (Anmeldeseite statt 500), nicht die Wirkung.
 *
 * <p>Ein Mismatch ist ausserdem in der Mehrzahl der Faelle <b>kein</b> Angriff: zwei Tabs oder zwei
 * Geraete, die denselben Cookie parallel erneuern, oder ein Cookie aus der Zeit vor einem
 * Datenbank-Reset erzeugen ihn genauso. Deshalb bleibt eine WARN-Zeile mit Serie und Kennung
 * stehen — der Unterschied zwischen harmlos und Angriff liegt in der Haeufung, nicht im Einzelfall,
 * und die Haeufung ist nur sichtbar, wenn jeder Fall protokolliert wird.
 */
@Slf4j
public class PlaintextRememberMeServices extends PersistentTokenBasedRememberMeServices {

    public PlaintextRememberMeServices(String key, UserDetailsService userDetailsService,
                                       PersistentTokenRepository tokenRepository) {
        super(key, userDetailsService, tokenRepository);
    }

    /**
     * Wie {@code super}, aber ohne die {@link CookieTheftException} nach draussen zu geben.
     *
     * <p>{@code null} ist die Antwort, die der {@code RememberMeAuthenticationFilter} als
     * „kein Auto-Login" versteht: die Anfrage laeuft anonym weiter und landet regulaer auf der
     * Anmeldeseite. Genau das ist das gewuenschte Verhalten fuer einen unbrauchbaren Cookie.
     */
    @Override
    public Authentication autoLogin(HttpServletRequest request, HttpServletResponse response) {
        try {
            return super.autoLogin(request, response);
        } catch (CookieTheftException ex) {
            // Kein Stacktrace: die Meldung von Spring Security nennt den Sachverhalt vollstaendig,
            // und ein Stacktrace pro Vorfall macht die Haeufung im Log schwerer lesbar.
            log.warn("SECURITY: remember-me Series/Token-Mismatch auf {} {} — alle persistenten "
                            + "Tokens des Benutzers sind verworfen, das Cookie ist geloescht. "
                            + "Der Aufrufer bekommt die Anmeldeseite statt HTTP 500 (Karte 898). "
                            + "Meldung: {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
            return null;
        }
    }
}

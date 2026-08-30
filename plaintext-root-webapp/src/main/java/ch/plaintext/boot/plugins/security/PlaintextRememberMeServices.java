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
 * Remember-me service that does not let a series/token mismatch run into an HTTP 500.
 *
 * <h2>Why this class exists (card 898)</h2>
 * On a mismatch Spring Security throws a {@link CookieTheftException}, and does so deliberately
 * all the way out: {@code AbstractRememberMeServices#autoLogin} catches it, deletes the cookie and
 * <b>rethrows</b> it. The {@code RememberMeAuthenticationFilter} does not wrap the
 * {@code autoLogin} call in its {@code catch (AuthenticationException)} — the exception
 * therefore propagates into the {@code dispatcherServlet}, and on
 * {@code /login.html} the user gets a <b>500 error page instead of a login form</b>.
 *
 * <p>Measured in the access report (card 892): six such 500s in seven days, each from a
 * real browser, each on the login page. In the application log before each one an expired
 * session ({@code Ajax-Request abgewiesen (CSRF-Token fehlt oder ist ungueltig)}), afterwards the
 * redirect to the login page — and there the 500. So whoever wants to log in sees a
 * broken system.
 *
 * <h2>Why this does not weaken the theft protection</h2>
 * The protection takes effect <b>before</b> the exception flies, and does not lie in propagating it:
 * {@code PersistentTokenBasedRememberMeServices#processAutoLoginCookie} calls
 * {@code tokenRepository.removeUserTokens(series)} and only then {@code throw}. All persistent
 * tokens of the user are therefore already discarded at that point, and {@code autoLogin} has deleted
 * the cookie via {@code cancelCookie}. This class therefore changes exclusively the
 * <b>response</b> (login page instead of 500), not the effect.
 *
 * <p>Besides, in the majority of cases a mismatch is <b>no</b> attack: two tabs or two
 * devices renewing the same cookie in parallel, or a cookie from before a
 * database reset produce it just as well. That is why a WARN line with series and identifier stays
 * in place — the difference between harmless and attack lies in the accumulation, not in the single
 * case, and the accumulation is only visible if every case is logged.
 */
@Slf4j
public class PlaintextRememberMeServices extends PersistentTokenBasedRememberMeServices {

    public PlaintextRememberMeServices(String key, UserDetailsService userDetailsService,
                                       PersistentTokenRepository tokenRepository) {
        super(key, userDetailsService, tokenRepository);
    }

    /**
     * Like {@code super}, but without letting the {@link CookieTheftException} out.
     *
     * <p>{@code null} is the answer that the {@code RememberMeAuthenticationFilter} understands as
     * "no auto login": the request continues anonymously and ends up on the login page in the regular
     * way. That is exactly the desired behaviour for an unusable cookie.
     */
    @Override
    public Authentication autoLogin(HttpServletRequest request, HttpServletResponse response) {
        try {
            return super.autoLogin(request, response);
        } catch (CookieTheftException ex) {
            // No stack trace: Spring Security's message states the facts completely,
            // and one stack trace per incident makes the accumulation harder to read in the log.
            log.warn("SECURITY: remember-me Series/Token-Mismatch auf {} {} — alle persistenten "
                            + "Tokens des Benutzers sind verworfen, das Cookie ist geloescht. "
                            + "Der Aufrufer bekommt die Anmeldeseite statt HTTP 500 (Karte 898). "
                            + "Meldung: {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
            return null;
        }
    }
}

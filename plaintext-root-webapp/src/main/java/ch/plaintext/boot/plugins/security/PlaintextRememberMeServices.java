/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ch.plaintext.boot.plugins.security.totp.TotpAuthenticationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.CookieTheftException;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationException;

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
 *
 * <h2>No remember-me for accounts with TOTP (card 1530, decision from card 1495)</h2>
 * A remember-me login skips the {@code PlaintextAuthenticationSuccessHandler} and with it the
 * TOTP step. If {@link TotpAuthenticationService#isTotpRequired(String)} is true for the account,
 * {@link #processAutoLoginCookie} therefore refuses: the user's persistent tokens are removed, the
 * cookie is cancelled by {@code autoLogin}, and the user logs in normally with the second factor.
 * This also closes the case "tenant switches TOTP off and on again": tokens issued in between no
 * longer work. Accounts without TOTP keep remember-me unchanged.
 */
@Slf4j
public class PlaintextRememberMeServices extends PersistentTokenBasedRememberMeServices {

    private final PersistentTokenRepository tokenRepository;
    private final TotpAuthenticationService totpAuthenticationService;

    public PlaintextRememberMeServices(String key, UserDetailsService userDetailsService,
                                       PersistentTokenRepository tokenRepository,
                                       TotpAuthenticationService totpAuthenticationService) {
        super(key, userDetailsService, tokenRepository);
        this.tokenRepository = tokenRepository;
        this.totpAuthenticationService = totpAuthenticationService;
    }

    /**
     * Like {@code super}, but refuses accounts that require TOTP (card 1530). The
     * {@link RememberMeAuthenticationException} is caught by {@code autoLogin}, which cancels the
     * cookie and returns {@code null} — the request continues anonymously to the login page.
     */
    @Override
    protected UserDetails processAutoLoginCookie(String[] cookieTokens, HttpServletRequest request,
                                                 HttpServletResponse response) {
        UserDetails user = super.processAutoLoginCookie(cookieTokens, request, response);
        if (totpAuthenticationService.isTotpRequired(user.getUsername())) {
            tokenRepository.removeUserTokens(user.getUsername());
            log.info("SECURITY: remember-me fuer {} abgelehnt — Konto verlangt TOTP, "
                    + "Anmeldung mit zweitem Faktor noetig (Karte 1530)", user.getUsername());
            throw new RememberMeAuthenticationException("Remember-me not allowed for accounts with TOTP");
        }
        return user;
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

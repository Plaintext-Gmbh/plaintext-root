/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collection;

/**
 * Builds a fully-fledged browser session from an already validated {@link UserDetails} — and does so
 * <b>along the same path as the form login</b>.
 *
 * <p><b>Why this class exists (card 309, security audit 24.07.2026):</b> {@code AutoLoginController}
 * and {@code TokenLoginController} used to assemble the {@link SecurityContext} themselves and
 * called {@link SecurityContextRepository#saveContext} directly. That skipped no fewer than three
 * protective mechanisms that apply automatically on the form/OAuth/OTT login:</p>
 * <ol>
 *   <li><b>Session fixation:</b> without {@link ChangeSessionIdAuthenticationStrategy} the session keeps
 *       its id. An attacker who plants a known session id on the victim in advance owns a fully
 *       authenticated session the moment the victim opens their auto/token login
 *       link.</li>
 *   <li><b>Account lockout:</b> {@code MyUserDetailsService} sets {@code accountNonLocked} based on the
 *       {@code AccountLockoutService}, but nobody evaluated the flag on these paths — an account
 *       locked because of brute force stayed open via auto/token login.</li>
 *   <li><b>Second factor (TOTP):</b> the 2FA gate sits in the {@link PlaintextAuthenticationSuccessHandler},
 *       which is only wired to {@code formLogin}/{@code oauth2Login}/{@code oneTimeTokenLogin}. Whoever
 *       creates the {@code Authentication} past it logs TOTP users in without a second factor.</li>
 * </ol>
 *
 * <p><b>State after card 560 (05.08.2026):</b> both original callers have been removed —
 * {@code AutoLoginController} with card 30, {@code TokenLoginController} with card 560. The class
 * stays nonetheless: it is the only place where a login path outside Spring Security's
 * own filters is finalized correctly, and precisely the absence of such a place was the
 * cause of the three gaps above. Whoever builds a login path in the future uses it — instead of
 * assembling the {@link SecurityContext} themselves again.</p>
 *
 * <p>The solution is deliberately <em>not</em> a reimplementation of the individual checks, but the
 * delegation to exactly the same components: {@link AccountStatusUserDetailsChecker},
 * {@link SessionAuthenticationStrategy} and {@link PlaintextAuthenticationSuccessHandler}. This way no
 * future gate (e.g. the enforced password change from card 306) can end up hanging off only one of
 * the login paths again.</p>
 */
@Component
@Slf4j
public class SessionLoginFinalizer {

    private final SecurityContextRepository securityContextRepository;
    private final PlaintextAuthenticationSuccessHandler successHandler;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy = new ChangeSessionIdAuthenticationStrategy();
    private final UserDetailsChecker userDetailsChecker = new AccountStatusUserDetailsChecker();

    public SessionLoginFinalizer(SecurityContextRepository securityContextRepository,
                                 PlaintextAuthenticationSuccessHandler successHandler) {
        this.securityContextRepository = securityContextRepository;
        this.successHandler = successHandler;
    }

    /**
     * Checks the account status, renews the session id, persists the {@link SecurityContext} and
     * then hands over to the {@link PlaintextAuthenticationSuccessHandler} (2FA gate, enforced
     * password change, start page redirect, login event).
     *
     * <p>The handler writes the redirect itself; the calling controller returns {@code null}
     * afterwards (the response is already committed by then).</p>
     *
     * @param userDetails validated user profile (origin: form login resp. ApiToken)
     * @param authorities authorities the session shall receive — as a parameter of its own, so that a
     *                    caller can narrow them in the future without duplicating the session logic
     * @param quelle      short designation of the login path for logging
     * @throws org.springframework.security.authentication.LockedException   account locked (lockout)
     * @throws org.springframework.security.authentication.DisabledException account disabled
     */
    public void finalizeLogin(UserDetails userDetails,
                              Collection<? extends GrantedAuthority> authorities,
                              String quelle,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException, ServletException {

        // (1) Account status: locked/disabled/expired -> exception, no login.
        userDetailsChecker.check(userDetails);

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities);

        // (2) Session fixation protection: new session id before the context is saved.
        sessionAuthenticationStrategy.onAuthentication(authToken, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authToken);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        log.info("{}: Session aufgebaut fuer {} (Session-Id erneuert)", quelle, userDetails.getUsername());

        // (3) 2FA gate / password change / start page / login event — identical to the form login.
        successHandler.onAuthenticationSuccess(request, response, authToken);
    }
}

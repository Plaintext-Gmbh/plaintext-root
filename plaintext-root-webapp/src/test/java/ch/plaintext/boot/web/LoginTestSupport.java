/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.plugins.security.PlaintextAuthenticationSuccessHandler;
import ch.plaintext.boot.plugins.security.PlaintextSecurityProperties;
import ch.plaintext.boot.plugins.security.SessionLoginFinalizer;
import ch.plaintext.boot.plugins.security.totp.TotpAuthenticationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Builds a <b>real</b> {@link SessionLoginFinalizer} together with a real
 * {@link PlaintextAuthenticationSuccessHandler} for the controller tests.
 *
 * <p><b>Why real and not mocked (card 309):</b> the finding was precisely that
 * {@code /autologin} and {@code /token-login} ran <em>past</em> session renewal, the lockout check
 * and the 2FA gate. A mocked finalizer would no longer notice exactly that. Only the real
 * path (incl. a real {@link HttpSessionSecurityContextRepository}) proves that the session id
 * changes and that a TOTP user ends up in the pending flow instead of in a full session.</p>
 */
final class LoginTestSupport {

    private LoginTestSupport() {
    }

    /** Assembled test setup incl. the adjustment points that individual tests need. */
    record Aufbau(SessionLoginFinalizer finalizer,
                  SecurityContextRepository securityContextRepository,
                  TotpAuthenticationService totpAuthenticationService,
                  PlaintextSecurityProperties securityProperties,
                  ApplicationEventPublisher eventPublisher) {
    }

    static Aufbau baueAuf() {
        SecurityContextRepository repo = spy(new HttpSessionSecurityContextRepository());
        TotpAuthenticationService totp = mock(TotpAuthenticationService.class);
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        PlaintextAuthenticationSuccessHandler handler = new PlaintextAuthenticationSuccessHandler(
                publisher,
                totp,
                repo,
                props,
                mock(PersistentTokenBasedRememberMeServices.class));
        return new Aufbau(new SessionLoginFinalizer(repo, handler), repo, totp, props, publisher);
    }
}

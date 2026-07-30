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
 * Baut fuer die Controller-Tests einen <b>echten</b> {@link SessionLoginFinalizer} samt echtem
 * {@link PlaintextAuthenticationSuccessHandler}.
 *
 * <p><b>Warum echt und nicht gemockt (Karte 309):</b> Der Befund war ja gerade, dass
 * {@code /autologin} und {@code /token-login} an Session-Erneuerung, Lockout-Pruefung und 2FA-Gate
 * <em>vorbei</em> liefen. Ein gemockter Finalizer wuerde genau das nicht mehr bemerken. Nur der echte
 * Pfad (inkl. echter {@link HttpSessionSecurityContextRepository}) belegt, dass sich die Session-Id
 * aendert und dass ein TOTP-User in den Pending-Flow statt in eine Vollsession geraet.</p>
 */
final class LoginTestSupport {

    private LoginTestSupport() {
    }

    /** Zusammengebauter Testaufbau inkl. der Stellschrauben, die einzelne Tests brauchen. */
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

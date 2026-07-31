/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.oidc;

import ch.plaintext.boot.plugins.security.PlaintextSecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SECURITY (Karte 314, Punkt 12) — Kontouebernahme beim OIDC-Account-Linking.
 *
 * <p>Findet der {@code PlaintextOidcUserService} kein Konto zum IdP-{@code sub}, sucht er per
 * Benutzername/E-Mail weiter und bindet ein gefundenes BESTEHENDES lokales Konto still an diesen
 * {@code sub}. Laesst der IdP unverifizierte Adressen zu, koennte sich jemand dort mit der Adresse
 * eines bestehenden Benutzers registrieren und danach dessen Konto uebernehmen. Beim erstmaligen
 * Verlinken wird deshalb {@code email_verified=true} verlangt — fail-closed, ein fehlender Claim
 * gilt als "nicht verifiziert".
 */
@DisplayName("OIDC-Linking: email_verified")
class PlaintextOidcUserServiceLinkingTest {

    private PlaintextOidcUserService serviceWith(boolean requireVerified) {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        props.setOidcRequireVerifiedEmail(requireVerified);
        return new PlaintextOidcUserService(null, null, props);
    }

    private void requireVerifiedEmail(PlaintextOidcUserService service, OidcUser user) throws Exception {
        Method m = PlaintextOidcUserService.class.getDeclaredMethod(
                "requireVerifiedEmail", OidcUser.class, String.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(service, user, "opfer@example.invalid", "email");
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private OidcUser userWithClaim(Object emailVerified) {
        OidcUser user = mock(OidcUser.class);
        when(user.getClaim("email_verified")).thenReturn(emailVerified);
        return user;
    }

    @Test
    void acceptsVerifiedEmail() {
        assertDoesNotThrow(() -> requireVerifiedEmail(serviceWith(true), userWithClaim(Boolean.TRUE)));
    }

    @Test
    void acceptsVerifiedEmailAsString() {
        assertDoesNotThrow(() -> requireVerifiedEmail(serviceWith(true), userWithClaim("true")));
    }

    @Test
    void rejectsUnverifiedEmail() {
        assertThrows(Exception.class,
                () -> requireVerifiedEmail(serviceWith(true), userWithClaim(Boolean.FALSE)));
    }

    /** Fail-closed: ein IdP ohne den Claim wird nicht stillschweigend akzeptiert. */
    @Test
    void rejectsMissingClaim() {
        assertThrows(Exception.class,
                () -> requireVerifiedEmail(serviceWith(true), userWithClaim(null)));
    }

    /**
     * Notausstieg fuer einen IdP, der den Claim nachweislich nicht liefert — damit der Fix keine
     * realen Anmeldungen aussperrt, ohne dass es eine Stellschraube gaebe.
     */
    @Test
    void canBeDisabledViaProperty() {
        assertDoesNotThrow(() -> requireVerifiedEmail(serviceWith(false), userWithClaim(null)));
    }
}

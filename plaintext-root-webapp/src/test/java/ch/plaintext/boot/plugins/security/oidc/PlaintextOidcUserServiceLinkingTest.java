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
 * SECURITY (card 314, item 12) — account takeover during OIDC account linking.
 *
 * <p>If the {@code PlaintextOidcUserService} finds no account for the IdP {@code sub}, it keeps
 * searching by user name/e-mail and silently binds an EXISTING local account it finds to that
 * {@code sub}. If the IdP allows unverified addresses, somebody could register there with the address
 * of an existing user and take over that user's account afterwards. On the first
 * linking {@code email_verified=true} is therefore required — fail-closed, a missing claim
 * counts as "not verified".
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

    /** Fail-closed: an IdP without the claim is not accepted silently. */
    @Test
    void rejectsMissingClaim() {
        assertThrows(Exception.class,
                () -> requireVerifiedEmail(serviceWith(true), userWithClaim(null)));
    }

    /**
     * Emergency exit for an IdP that demonstrably does not deliver the claim — so that the fix does not
     * lock out real logins without there being an adjusting screw.
     */
    @Test
    void canBeDisabledViaProperty() {
        assertDoesNotThrow(() -> requireVerifiedEmail(serviceWith(false), userWithClaim(null)));
    }
}

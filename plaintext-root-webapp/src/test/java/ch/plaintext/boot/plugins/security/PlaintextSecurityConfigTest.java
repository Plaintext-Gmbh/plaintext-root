/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.plugins.security.magiclink.HashedOneTimeTokenService;
import ch.plaintext.boot.plugins.security.magiclink.MagicLinkGenerationSuccessHandler;
import ch.plaintext.boot.plugins.security.oidc.JdbcClientRegistrationRepository;
import ch.plaintext.boot.plugins.security.oidc.PlaintextOidcUserService;
import ch.plaintext.boot.plugins.security.service.MyRememberMeRepositoryRepository;
import ch.plaintext.boot.plugins.security.service.MyUserDetailsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests for PlaintextSecurityConfig - security configuration beans.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaintextSecurityConfigTest {

    @Mock
    private MyRememberMeRepositoryRepository tokenRepository;

    @Mock
    private MyUserDetailsService userDetailsService;

    @Mock
    private PlaintextAuthenticationSuccessHandler successHandler;

    @Mock
    private PlaintextSecurityProperties securityProperties;

    @Mock
    private JdbcClientRegistrationRepository clientRegistrationRepository;

    @Mock
    private PlaintextOidcUserService oidcUserService;

    @Mock
    private HashedOneTimeTokenService hashedOneTimeTokenService;

    @Mock
    private MagicLinkGenerationSuccessHandler magicLinkGenerationSuccessHandler;

    private PlaintextSecurityConfig createConfig() {
        return createConfig(new org.springframework.mock.env.MockEnvironment());
    }

    private PlaintextSecurityConfig createConfig(org.springframework.core.env.Environment environment) {
        return new PlaintextSecurityConfig(
                tokenRepository, userDetailsService, successHandler,
                securityProperties, clientRegistrationRepository, oidcUserService,
                hashedOneTimeTokenService, magicLinkGenerationSuccessHandler, environment);
    }

    private static org.springframework.core.env.Environment prodEnvironment() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    @Test
    void passwordEncoder_shouldReturnBCryptEncoder() {
        PlaintextSecurityConfig config = createConfig();

        PasswordEncoder encoder = config.passwordEncoder();

        assertNotNull(encoder);
        assertTrue(encoder instanceof BCryptPasswordEncoder);
    }

    @Test
    void passwordEncoder_shouldEncodeAndMatchPassword() {
        PlaintextSecurityConfig config = createConfig();

        PasswordEncoder encoder = config.passwordEncoder();
        String raw = "testPassword123";
        String encoded = encoder.encode(raw);

        assertTrue(encoder.matches(raw, encoded));
        assertFalse(encoder.matches("wrongPassword", encoded));
    }

    @Test
    void securityContextRepository_shouldReturnHttpSessionRepository() {
        // Die Repository-Bean wurde in SecurityContextRepositoryConfig ausgelagert
        // (bricht die Konstruktor-Zyklus-Abhaengigkeit des TOTP-Gates).
        SecurityContextRepository repo = new SecurityContextRepositoryConfig().securityContextRepository();

        assertNotNull(repo);
    }

    @Test
    void rememberMeServices_shouldReturnService() {
        PlaintextSecurityConfig config = createConfig();

        assertNotNull(config.rememberMeServices());
    }

    @Test
    void rememberMeKey_shouldFallBackToGeneratedKeyWhenBlank() {
        when(securityProperties.getRememberMeKey()).thenReturn("");

        PlaintextSecurityConfig config = createConfig();

        // The bean is created without exceptions and the services instance is non-null
        // even when no key is configured. The actual key value is not exposed by the
        // bean, but a NullPointerException would have been thrown by Spring Security
        // if the key were empty/null.
        PersistentTokenBasedRememberMeServices services = config.rememberMeServices();
        assertNotNull(services);
    }

    @Test
    void rememberMeKey_shouldUseConfiguredKey() {
        when(securityProperties.getRememberMeKey())
                .thenReturn("my-stable-32-byte-secret-from-vault");

        PlaintextSecurityConfig config = createConfig();

        PersistentTokenBasedRememberMeServices services = config.rememberMeServices();
        assertNotNull(services);
    }

    /**
     * SECURITY (Karte 314, Punkt 7): der BCrypt-Kostenfaktor muss 12 sein, nicht der
     * Spring-Default 10. Der Faktor steht im Hash-Präfix ({@code $2a$12$...}) und laesst sich
     * dort direkt ablesen.
     */
    @Test
    void passwordEncoder_shouldUseCostFactor12() {
        PasswordEncoder encoder = createConfig().passwordEncoder();

        String encoded = encoder.encode("testPassword123");

        assertTrue(encoded.startsWith("$2a$12$"),
                "Erwartet BCrypt-Kostenfaktor 12, war: " + encoded.substring(0, 7));
    }

    /**
     * SECURITY (Karte 314, Punkt 13): in PROD ist ein stabiler Remember-Me-Key Pflicht. Bisher
     * gab es nur eine WARN und einen fluechtigen Zufallsschluessel — funktional unauffaellig,
     * deshalb faellt ein fehlender Schluessel im Betrieb nie auf.
     */
    @Test
    void rememberMeKey_shouldFailFastInProductionWhenMissing() {
        when(securityProperties.getRememberMeKey()).thenReturn("");

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> createConfig(prodEnvironment()));

        assertTrue(ex.getMessage().contains("remember-me-key"));
    }

    /** In dev/test bleibt der Zufallsschluessel erhalten — lokaler Start ohne Env muss gehen. */
    @Test
    void rememberMeKey_shouldNotFailOutsideProduction() {
        when(securityProperties.getRememberMeKey()).thenReturn("");

        assertNotNull(createConfig().rememberMeServices());
    }

    /**
     * SECURITY (Karte 314, Punkt 4): {@code /api/preferences/**} darf NICHT mehr in der
     * CSRF-Ausnahmeliste stehen — die Endpunkte sind session-authentifiziert und waren damit
     * per cross-site-POST ansteuerbar.
     */
    @Test
    void csrfIgnoreList_shouldNotContainPreferencesApi() {
        assertFalse(defaultCsrfIgnore().contains("/api/preferences/**"),
                "/api/preferences/** ist session-authentifiziert und braucht CSRF-Schutz");
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> defaultCsrfIgnore() {
        try {
            java.lang.reflect.Field f = PlaintextSecurityConfig.class.getDeclaredField("DEFAULT_CSRF_IGNORE");
            f.setAccessible(true);
            return (java.util.List<String>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

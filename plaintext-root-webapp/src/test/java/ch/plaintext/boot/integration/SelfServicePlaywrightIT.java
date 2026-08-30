/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.selfservice.PasswordResetTokenRepository;
import ch.plaintext.boot.plugins.security.selfservice.RegistrationTokenRepository;
import ch.plaintext.settings.entity.SetupConfig;
import ch.plaintext.settings.service.SetupConfigService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Playwright test for the self-registration and password-reset
 * flows added in this PR.
 *
 * <p>Runs against an embedded PostgreSQL on a random port so the test
 * is hermetic and works both on CI and on a developer laptop. Browsers are
 * installed by the {@code playwright} GitHub workflow before this test
 * executes; locally the test will skip itself silently if Chromium has not
 * yet been installed (just run {@code mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI"
 * -Dexec.args="install --with-deps chromium" -pl plaintext-root-webapp}).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.docker.compose.enabled=false"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelfServicePlaywrightIT {

    // Formerly a Testcontainers Postgres with @ServiceConnection ran here: @DynamicPropertySource
    // had read 'spring.datasource.url' already during the condition evaluation, before the
    // container was up ("Mapped port can only be obtained after the container is started").
    // With the embedded server that problem disappears — it is already running when the registry
    // is filled (static start in EmbeddedPg).
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "selfserviceplaywrightit");
    }

    @LocalServerPort int port;
    @Autowired SetupConfigService setupConfigService;
    @Autowired RegistrationTokenRepository registrationTokenRepository;
    @Autowired PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired MyUserRepository userRepository;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @BeforeAll
    void launchBrowser() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        } catch (RuntimeException e) {
            // Browsers not installed locally — skip the whole class.
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Chromium not installed: " + e.getMessage());
        }
    }

    @AfterAll
    void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void newContext() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    @Test
    void registrationFlow_whenEnabled_walksUserThroughVerificationAndSetsPassword() {
        // Operator turns the flow on for the default mandant.
        SetupConfig cfg = setupConfigService.getOrCreate("default");
        cfg.setSelfRegistrationEnabled(true);
        setupConfigService.save(cfg);

        // 1. User opens the registration form and submits an address.
        page.navigate(url("/register"));
        assertTrue(page.content().contains("Konto anlegen"));
        page.fill("input[name=email]", "playwright-user@example.com");
        page.click("button[type=submit]");
        assertTrue(page.content().contains("Posteingang"),
                "expected confirmation page, got: " + page.content());

        // 2. Card 307, K2.3: the DB now holds ONLY the SHA-256 hash, never the plaintext. We set
        //    the stored hash to that of a plaintext token known to us and use this one in the
        //    verification link — just as the user would get it from the e-mail. That tests the hashing
        //    end-to-end at the same time (the verify step hashes the plaintext and has to hit the hash).
        String token = "playwright-known-registration-token";
        var regToken = registrationTokenRepository.findAll().stream()
                .filter(t -> t.getEmail().equals("playwright-user@example.com"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Registration token not persisted"));
        regToken.setTokenHash(sha256Hex(token));
        registrationTokenRepository.save(regToken);

        // 3. Click the link the user would have got from the e-mail.
        page.navigate(url("/register/verify?token=" + token));
        assertTrue(page.content().contains("Passwort wählen"));
        page.fill("input[name=password]", "playwright-password");
        page.click("button[type=submit]");
        assertTrue(page.content().contains("Willkommen"),
                "expected success page, got: " + page.content());

        // 4. The MyUserEntity exists with the right mandant role.
        assertNotNull(userRepository.findByUsername("playwright-user@example.com"));
        assertTrue(userRepository.findByUsername("playwright-user@example.com")
                .getRoles().contains("PROPERTY_MANDAT_default"));
    }

    @Test
    void passwordResetFlow_whenEnabled_replacesPasswordOnExistingAccount() {
        SetupConfig cfg = setupConfigService.getOrCreate("default");
        cfg.setSelfRegistrationEnabled(true);
        cfg.setPasswordResetLinkEnabled(true);
        setupConfigService.save(cfg);

        // Bootstrap a user via the registration flow so we don't depend on a
        // pre-baked fixture.
        ch.plaintext.boot.plugins.security.model.MyUserEntity user =
                new ch.plaintext.boot.plugins.security.model.MyUserEntity();
        user.setUsername("reset-target@example.com");
        user.setPassword("OLD-HASH");
        user.addRole("ROLE_USER");
        userRepository.save(user);

        // 1. Request a reset.
        page.navigate(url("/password-reset"));
        page.fill("input[name=username]", "reset-target@example.com");
        page.click("button[type=submit]");
        assertTrue(page.content().contains("Posteingang"));

        // 2. Card 307, K2.3: the DB holds only the hash — we set it to that of a known plaintext
        //    token and use this one in the confirm link (as if from the e-mail).
        String token = "playwright-known-reset-token";
        var resetToken = passwordResetTokenRepository.findAll().stream()
                .filter(t -> t.getUsername().equals("reset-target@example.com"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Reset token not persisted"));
        resetToken.setTokenHash(sha256Hex(token));
        passwordResetTokenRepository.save(resetToken);

        // 3. Visit the confirm page and set a new password.
        page.navigate(url("/password-reset/confirm?token=" + token));
        page.fill("input[name=password]", "brand-new-password");
        page.click("button[type=submit]");
        assertTrue(page.content().contains("Erledigt"),
                "expected success page, got: " + page.content());

        // 4. Hash on the user changed.
        ch.plaintext.boot.plugins.security.model.MyUserEntity reloaded =
                userRepository.findByUsername("reset-target@example.com");
        assertNotEquals("OLD-HASH", reloaded.getPassword(),
                "password hash should have been replaced");
    }

    @Test
    void registrationFlow_whenDisabled_quietlyAcceptsButCreatesNoUser() {
        // Self-registration explicitly OFF. Do not rely on the default: other tests in this
        // class (PER_CLASS, shared container) set the flag for "default" to true and store
        // it -> without an explicit reset this test would, depending on the order, run with active
        // registration and would create a token after all.
        SetupConfig cfg = setupConfigService.getOrCreate("default");
        cfg.setSelfRegistrationEnabled(false);
        setupConfigService.save(cfg);

        page.navigate(url("/register"));
        page.fill("input[name=email]", "blocked@example.com");
        page.click("button[type=submit]");
        assertTrue(page.content().contains("Posteingang"),
                "we still show a generic confirmation to avoid leaking the toggle state");
        assertNull(userRepository.findByUsername("blocked@example.com"));
        assertTrue(registrationTokenRepository.findAll().stream()
                .noneMatch(t -> t.getEmail().equals("blocked@example.com")));
    }

    /** SHA-256 hex of a plaintext token (card 307, K2.3) — identical to the server-side computation. */
    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

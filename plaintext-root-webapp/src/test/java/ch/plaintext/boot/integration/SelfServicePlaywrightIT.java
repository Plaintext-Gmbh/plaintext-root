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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Playwright test for the self-registration and password-reset
 * flows added in this PR.
 *
 * <p>Runs against a Testcontainers PostgreSQL on a random port so the test
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
@Testcontainers
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelfServicePlaywrightIT {

    // @ServiceConnection (statt @DynamicPropertySource): Spring Boot löst die DB-Connection-Details
    // erst beim Erzeugen des DataSource-Beans auf – dann ist der von @Testcontainers gestartete
    // Container garantiert oben. Mit dem alten @DynamicPropertySource wurde 'spring.datasource.url'
    // bereits während der frühen Condition-Auswertung (DataSourceAutoConfiguration) gelesen, bevor der
    // Container lief -> "Mapped port can only be obtained after the container is started".
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("plaintext_test")
            .withUsername("test")
            .withPassword("test");

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

        // 2. Karte 307, K2.3: In der DB liegt jetzt NUR der SHA-256-Hash, nie der Klartext. Wir setzen
        //    den gespeicherten Hash auf den eines uns bekannten Klartext-Tokens und benutzen diesen im
        //    Verifikations-Link — so, wie der Nutzer ihn aus der E-Mail bekaeme. Das testet das Hashing
        //    zugleich end-to-end (der Verify-Schritt hasht den Klartext und muss den Hash treffen).
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

        // 2. Karte 307, K2.3: DB haelt nur den Hash — wir setzen ihn auf den eines bekannten Klartext-
        //    Tokens und benutzen diesen im Confirm-Link (wie aus der E-Mail).
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
        // Selbstregistrierung explizit AUS. Nicht auf den Default verlassen: andere Tests in dieser
        // Klasse (PER_CLASS, geteilter Container) setzen das Flag für "default" auf true und speichern
        // es -> ohne explizites Zurücksetzen liefe dieser Test je nach Reihenfolge mit aktiver
        // Registrierung und legte doch ein Token an.
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

    /** SHA-256-Hex eines Klartext-Tokens (Karte 307, K2.3) — identisch zur Server-Berechnung. */
    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

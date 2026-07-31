/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Playwright-basierter Integration Test, der alle Seiten der Applikation aufruft
 * und prüft, dass keine Server-Fehler auftreten.
 *
 * Voraussetzung: PostgreSQL läuft (docker compose up / ./start start-pg)
 *
 * Ausführen:
 *   mvn failsafe:integration-test failsafe:verify -pl plaintext-root-webapp -Dit.test=PlaywrightPageIT
 *
 * Oder über das Shell-Script:
 *   ./run-playwright-tests.sh
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.docker.compose.enabled=false",
                "spring.datasource.url=jdbc:postgresql://localhost:5434/plaintext_root",
                "spring.datasource.username=plaintext",
                "spring.datasource.password=plaintext",
                "spring.flyway.enabled=true",
                "spring.flyway.baseline-on-migrate=true",
                "spring.flyway.out-of-order=true",
                "spring.flyway.validate-on-migrate=false"
        }
)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "Requires local PostgreSQL at localhost:5434")
class PlaywrightPageIT {

    @LocalServerPort
    private int port;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @BeforeAll
    void setupBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    void teardownBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void createContext() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    /**
     * Login über das Spring Security Formular. Verwendet den beim Boot von
     * {@code PlaintextInitLoader} angelegten Root-User (root@root.root / root, Rollen root+admin+user) —
     * das frühere admin/admin stammte aus einer veralteten test-data.sql (Tabelle user_account existiert
     * nicht mehr) und schlug immer fehl.
     */
    private void login() {
        page.navigate(baseUrl() + "/login.html");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        page.fill("input[name='username']", "root@root.root");
        page.fill("input[name='password']", "root");
        page.click("button[type='submit']");
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    /**
     * Prüft ob eine Seite ohne Server-Fehler geladen wird.
     */
    private PageLoadResult loadPage(String path) {
        List<String> consoleErrors = new ArrayList<>();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                consoleErrors.add(msg.text());
            }
        });

        Response response = page.navigate(baseUrl() + path,
                new Page.NavigateOptions().setTimeout(30000));
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);

        int status = response != null ? response.status() : 0;
        String finalUrl = page.url();

        boolean hasViewExpired = page.locator("text=ViewExpiredException").count() > 0;
        boolean hasServerError = page.locator("text=Internal Server Error").count() > 0;

        return new PageLoadResult(path, status, finalUrl, consoleErrors, hasViewExpired, hasServerError);
    }

    record PageLoadResult(
            String path, int status, String finalUrl,
            List<String> consoleErrors, boolean hasViewExpired, boolean hasServerError
    ) {
        boolean isSuccess() {
            if (status >= 500) return false;
            if (hasViewExpired) return false;
            if (hasServerError) return false;
            return true;
        }
    }

    // ─── Unauthenticated Pages ──────────────────────────────────

    @Test
    @Order(1)
    void loginPage_shouldLoad() {
        PageLoadResult result = loadPage("/login.html");
        assertTrue(result.isSuccess(), "Login page failed: status=" + result.status());
    }

    @Test
    @Order(2)
    void nosecVersion_shouldLoad() {
        PageLoadResult result = loadPage("/nosec/version");
        assertTrue(result.isSuccess(), "Version endpoint failed: status=" + result.status());
    }

    @Test
    @Order(3)
    void actuatorHealth_shouldNotReturn500() {
        PageLoadResult result = loadPage("/actuator/health");
        assertTrue(result.status() < 500,
                "Actuator health returned server error: " + result.status());
    }

    // ─── Login ──────────────────────────────────────────────────

    @Test
    @Order(10)
    void login_shouldSucceed() {
        login();
        assertFalse(page.url().contains("login"),
                "Login failed - still on login page: " + page.url());
    }

    // ─── Authenticated Pages (parametrisiert) ───────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @Order(20)
    @ValueSource(strings = {
            "/index.html",
            "/myuser.html",
            "/i18n-translations.html",
            "/useradmin.html",
            "/mandate.html",
            "/settings.html",
            "/setup.html",
            "/sessions.html",
            "/sessioninsights.html",
            "/cron.html",
            "/flyway.html",
            "/rootentities.html",
            "/adminentities.html",
            "/mandatemenu.html",
            "/rollenzuteilung.html",
            "/anforderungen.html",
            "/howtos.html",
            "/claudesummary.html",
            "/anforderungssettings.html",
            "/filelist.html",
            "/debug.html",
            "/performance.html",
    })
    void page_shouldLoadWithoutServerError(String path) {
        login();
        PageLoadResult result = loadPage(path);

        assertFalse(result.hasViewExpired(),
                "ViewExpiredException on " + path);
        assertTrue(result.status() < 500,
                path + " returned status " + result.status());
    }

    // ─── Smoke Test Report ──────────────────────────────────────

    @Test
    @Order(100)
    void allPages_smokeTestReport() {
        login();

        String[] allPages = {
                "/index.html", "/i18n-translations.html", "/myuser.html", "/useradmin.html", "/mandate.html",
                "/settings.html", "/setup.html",
                "/sessions.html", "/sessioninsights.html",
                "/cron.html", "/flyway.html",
                "/rootentities.html", "/adminentities.html",
                "/mandatemenu.html", "/rollenzuteilung.html",
                "/anforderungen.html", "/howtos.html", "/claudesummary.html",
                "/anforderungssettings.html",
                "/filelist.html",
                "/debug.html", "/performance.html"
        };

        List<String> failures = new ArrayList<>();
        int successCount = 0;

        for (String path : allPages) {
            try {
                PageLoadResult result = loadPage(path);
                if (!result.isSuccess()) {
                    failures.add(path + " -> status=" + result.status()
                            + (result.hasViewExpired() ? " [ViewExpired]" : "")
                            + (result.hasServerError() ? " [ServerError]" : ""));
                } else {
                    successCount++;
                }
            } catch (Exception e) {
                failures.add(path + " -> " + e.getMessage());
            }
        }

        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  Playwright Smoke Test Report");
        System.out.println("══════════════════════════════════════════");
        System.out.println("  Successful: " + successCount + "/" + allPages.length);
        if (!failures.isEmpty()) {
            System.out.println("  Failures:   " + failures.size());
            failures.forEach(f -> System.out.println("    - " + f));
        }
        System.out.println("══════════════════════════════════════════\n");

        assertTrue(failures.isEmpty(),
                "Pages with errors:\n" + String.join("\n", failures));
    }
}

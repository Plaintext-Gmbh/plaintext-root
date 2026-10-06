/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.testsupport.EmbeddedPg;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 1348: the global search in the topbar finds the menu entries of the left navigation
 * <b>with typos</b>, in a real browser — type, see the list narrow down, click or press Enter,
 * land on the page. And it never offers a page the user may not open.
 *
 * <p>Every positive statement has its counter-check here:</p>
 * <ul>
 *   <li>{@link #tippfehlerFindetMenuepunktUndKlickNavigiert()} — "rolenzuteilung" (one l missing)
 *       lists "Rollenzuteilung", the click lands on {@code rollenzuteilung.html}.</li>
 *   <li>{@link #enterOeffnetDenErstenTreffer()} — the same with Enter and no arrow key.</li>
 *   <li>{@link #menuepunktOhneRechtErscheintNicht()} — an ADMIN typing "menusteurung" does
 *       <b>not</b> get the ROOT-only "Menüsteuerung", while a ROOT user with the very same query
 *       does. Without the second half this would also be green if the fuzzy search found nothing
 *       at all.</li>
 *   <li>{@link #unsinnLiefertKeineTreffer()} — a nonsense word shows "Keine Treffer" and no entry.</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.docker.compose.enabled=false"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GlobaleSucheMenuPlaywrightIT {

    private static final String ROOT_USER = "pw-suche-root";
    private static final String ADMIN_USER = "pw-suche-admin";
    private static final String PASSWORT = "Playwright-2026!";

    private static final String EINGABE = "#global-search-input";
    private static final String TREFFER = "#global-search-results .global-search-item";
    /** Rendered results: either hits or the "no hits" line. */
    private static final String ERGEBNIS = TREFFER + ", #global-search-results .global-search-empty";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "globalesuchemenuplaywrightit");
    }

    @LocalServerPort int port;
    @Autowired MyUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @BeforeAll
    void launchBrowserUndBenutzer() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        } catch (RuntimeException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Chromium not installed: " + e.getMessage());
        }
        // Roles WITHOUT the "ROLE_" prefix (see RootPagesPlaywrightIT).
        benutzerAnlegen(ROOT_USER, "ROOT", "ADMIN", "USER");
        benutzerAnlegen(ADMIN_USER, "ADMIN", "USER");
    }

    private void benutzerAnlegen(String name, String... rollen) {
        if (userRepository.findByUsername(name) != null) {
            return;
        }
        MyUserEntity user = new MyUserEntity();
        user.setUsername(name);
        user.setPassword(passwordEncoder.encode(PASSWORT));
        for (String rolle : rollen) {
            user.addRole(rolle);
        }
        user.setMandat("default");
        userRepository.save(user);
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

    private void anmelden(String benutzer) {
        page.navigate(url("/login.html"));
        page.fill("#username", benutzer);
        page.fill("#password", PASSWORT);
        page.locator("#password").press("Enter");
        page.waitForLoadState();
        assertFalse(page.url().contains("login"), "Login als " + benutzer + " schlug fehl: " + page.url());
    }

    /** Raw answer of the last search, for the failure messages. */
    private String letzteAntwort = "";

    /**
     * Types like a person (key by key, so the list narrows while typing) and returns the titles
     * rendered for the <b>complete</b> word: waits for the answer of {@code /api/search} to exactly
     * this query, then for the list to be drawn from it.
     */
    private List<String> suchen(String begriff) {
        Locator eingabe = page.locator(EINGABE);
        eingabe.click();
        String erwartet = "/api/search?q=" + URLEncoder.encode(begriff, StandardCharsets.UTF_8);
        Response antwort = page.waitForResponse(r -> r.url().endsWith(erwartet),
                () -> eingabe.pressSequentially(begriff, new Locator.PressSequentiallyOptions().setDelay(40)));
        letzteAntwort = antwort.status() + " " + antwort.text();
        page.locator(ERGEBNIS).first().waitFor();
        // render() runs after r.json() resolves - a short grace period for the drawing.
        page.waitForTimeout(300);
        return page.locator(TREFFER + " .gs-title").allInnerTexts();
    }

    @Test
    void tippfehlerFindetMenuepunktUndKlickNavigiert() {
        anmelden(ADMIN_USER);
        List<String> titel = suchen("rolenzuteilung");
        assertTrue(titel.contains("Rollenzuteilung"), "Tippfehler muss treffen, gefunden: " + titel + " | /api/search: " + letzteAntwort);

        page.locator(TREFFER).filter(new Locator.FilterOptions().setHasText("Rollenzuteilung")).first().click();
        page.waitForURL("**/rollenzuteilung.html**");
        assertTrue(page.url().contains("rollenzuteilung.html"), page.url());
    }

    @Test
    void enterOeffnetDenErstenTreffer() {
        anmelden(ADMIN_USER);
        List<String> titel = suchen("rollenzuteilnug");
        assertEquals("Rollenzuteilung", titel.isEmpty() ? null : titel.get(0),
                "bester Treffer zuoberst, gefunden: " + titel + " | /api/search: " + letzteAntwort);

        page.locator(EINGABE).press("Enter");
        page.waitForURL("**/rollenzuteilung.html**");
        assertTrue(page.url().contains("rollenzuteilung.html"), page.url());
    }

    @Test
    void menuepunktOhneRechtErscheintNicht() {
        anmelden(ADMIN_USER);
        List<String> alsAdmin = suchen("menusteurung");
        assertFalse(alsAdmin.contains("Menüsteuerung"),
                "ROOT-Menüpunkt darf ADMIN nicht angeboten werden: " + alsAdmin + " | /api/search: " + letzteAntwort);
        context.close();

        // Positive control: the same query as ROOT does find it — so the ADMIN result above is the
        // rights check and not a search that finds nothing.
        context = browser.newContext();
        page = context.newPage();
        anmelden(ROOT_USER);
        List<String> alsRoot = suchen("menusteurung");
        assertTrue(alsRoot.contains("Menüsteuerung"), "ROOT muss den Menüpunkt finden: " + alsRoot + " | /api/search: " + letzteAntwort);
    }

    @Test
    void unsinnLiefertKeineTreffer() {
        anmelden(ROOT_USER);
        List<String> titel = suchen("qxqxqxqx");
        assertTrue(titel.isEmpty(), "Unsinn darf nichts finden: " + titel + " | /api/search: " + letzteAntwort);
        assertTrue(page.locator("#global-search-results .global-search-empty").isVisible());
    }
}

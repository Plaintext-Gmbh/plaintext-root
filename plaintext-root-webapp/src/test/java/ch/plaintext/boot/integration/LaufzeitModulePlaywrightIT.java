/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.lader.ModulLader;
import ch.plaintext.testsupport.EmbeddedPg;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1515: die Laufzeit-Module in module.html im echten Browser. Der Knopf muss die Datei im PV wirklich
 * schreiben (gerenderte Seite allein beweist nichts), ROOT schreibt und macht Rollback, ADMIN kommt nicht hin.
 * Aufbau wie {@link RootPagesPlaywrightIT}; ohne Chromium ueberspringt sich die Klasse.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.docker.compose.enabled=false")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LaufzeitModulePlaywrightIT {

    private static final String ROOT_USER = "pw-lz-root";
    private static final String ADMIN_USER = "pw-lz-admin";
    private static final String PASSWORT = "Playwright-2026!";
    private static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("laufzeit-module-it");
            Files.writeString(DIR.resolve("registry.yaml"), """
                    format: 2
                    module:
                    - name: plaintext-z-kontakte
                      klasse: app
                      art: modul
                      versionen:
                      - {version: 2.1.0, root-min: 1.775.0, sha256: %s, api: [], pflicht: [], optional: [], bibliotheken: []}
                    - name: plaintext-z-rechnungen
                      klasse: app
                      art: modul
                      versionen:
                      - {version: 2.1.0, root-min: 1.775.0, sha256: %s, api: [], pflicht: [plaintext-z-kontakte], optional: [], bibliotheken: []}
                    """.formatted("a".repeat(64), "b".repeat(64)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "laufzeitmoduleplaywrightit");
        registry.add(ModulLader.VERZEICHNIS_ENV, DIR::toString);
    }

    @LocalServerPort int port;
    @Autowired MyUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    @BeforeAll
    void start() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        } catch (RuntimeException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Chromium not installed: " + e.getMessage());
        }
        benutzer(ROOT_USER, "ROOT", "ADMIN", "USER");
        benutzer(ADMIN_USER, "ADMIN", "USER");
    }

    private void benutzer(String name, String... rollen) {
        if (userRepository.findByUsername(name) != null) {
            return;
        }
        MyUserEntity u = new MyUserEntity();
        u.setUsername(name);
        u.setPassword(passwordEncoder.encode(PASSWORT));
        for (String r : rollen) {
            u.addRole(r);
        }
        u.setMandat("default");
        userRepository.save(u);
    }

    @AfterAll
    void stop() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void neu() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void zu() {
        if (context != null) context.close();
    }

    private String url(String p) {
        return "http://localhost:" + port + p;
    }

    private void anmelden(String benutzer) {
        page.navigate(url("/login.html"));
        page.fill("#username", benutzer);
        page.fill("#password", PASSWORT);
        page.locator("#password").press("Enter");
        page.waitForLoadState();
        assertFalse(page.url().contains("login"), "Login als " + benutzer + " schlug fehl: " + page.url());
    }

    private void ruhig() {
        page.waitForFunction("() => window.jQuery && jQuery.active === 0 && jQuery(':animated').length === 0"
                + " && (!window.PrimeFaces || PrimeFaces.ajax.Queue.isEmpty())");
    }

    /** Den sichtbaren Ja-Knopf des globalen Bestaetigungsdialogs. */
    private void ja() {
        page.locator(".ui-confirm-dialog .ui-confirmdialog-yes:visible").click();
    }

    private void schreibenMit(String... module) {
        Response r = page.navigate(url("/module.html"));
        assertEquals(200, r.status(), "module.html: HTTP " + r.status());
        page.locator("[id=\"fm:laufzeit\"] .ui-fieldset-legend").click();
        ruhig();
        for (String m : module) {
            page.locator("[id=\"fm:auswahl\"] label").filter(new com.microsoft.playwright.Locator.FilterOptions()
                    .setHasText(m)).first().click();
        }
        page.locator("[id=\"fm:btnLaufzeitSchreiben\"]").click();
        ja();
        ruhig();
        page.waitForSelector("text=Konfig geschrieben");
    }

    @Test
    void rootSchreibtUndMachtRollback() throws Exception {
        Path konfig = DIR.resolve(ModulLader.KONFIG);
        Files.deleteIfExists(konfig);
        Files.deleteIfExists(DIR.resolve(ModulLader.KONFIG_VORHER));
        anmelden(ROOT_USER);

        schreibenMit("plaintext-z-kontakte");
        String erster = Files.readString(konfig);
        assertEquals(java.util.List.of("plaintext-z-kontakte:2.1.0"), ModulLader.konfigPruefen(erster));

        // neu laden: die Auswahl kommt aus der Konfig (kontakte angehakt), rechnungen dazu
        schreibenMit("plaintext-z-rechnungen");
        assertTrue(Files.readString(konfig).contains("plaintext-z-rechnungen"), Files.readString(konfig));

        page.locator("[id=\"fm:btnLaufzeitRollback\"]").click();
        ja();
        ruhig();
        page.waitForSelector("text=Vorheriger Stand zurueck");
        assertEquals(erster, Files.readString(konfig));
    }

    @Test
    void adminKommtNichtAnDieSeite() throws Exception {
        anmelden(ADMIN_USER);
        String vorher = Files.exists(DIR.resolve(ModulLader.KONFIG)) ? Files.readString(DIR.resolve(ModulLader.KONFIG)) : null;
        Response r = page.navigate(url("/module.html"));
        page.waitForLoadState();
        boolean gesperrt = r.status() != 200 || !page.url().contains("module");
        assertTrue(gesperrt, "ADMIN sieht module.html: " + page.url());
        assertEquals(0, page.locator("[id=\"fm:laufzeit\"]").count(), "Abschnitt sichtbar fuer ADMIN");
        String nachher = Files.exists(DIR.resolve(ModulLader.KONFIG)) ? Files.readString(DIR.resolve(ModulLader.KONFIG)) : null;
        assertEquals(vorher, nachher);
    }
}

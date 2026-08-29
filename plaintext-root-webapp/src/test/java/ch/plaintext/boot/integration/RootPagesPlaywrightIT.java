/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import ch.plaintext.settings.ISettingsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auftrag Daniel, 29.08.2026: Die Root-/Admin-Seiten im echten Browser — genau die vier Befunde von
 * app.guild42.ch, damit sie nicht wiederkommen:
 *
 * <ol>
 *   <li>{@code menudiagnose.html} rendert die Tabelle (Record-EL-Fehler),</li>
 *   <li>{@code rootentities.html}: die Typ-Auswahl zeigt die Tabelle (Ajax statt {@code submit()}),</li>
 *   <li>„Swagger" fehlt im Menue, solange springdoc aus ist,</li>
 *   <li>die Menuesteuerungs-Anleitung ist ohne Menuepunkt ueber den Info-Knopf erreichbar,</li>
 *   <li>Mailtexte sind fuer einen ADMIN erreichbar und im Menue verlinkt.</li>
 * </ol>
 *
 * <p>Aufbau wie {@link SelfServicePlaywrightIT}: eingebettetes PostgreSQL, Chromium headless; ohne
 * installiertes Chromium ueberspringt sich die Klasse still.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.docker.compose.enabled=false"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RootPagesPlaywrightIT {

    private static final String ROOT_USER = "pw-root";
    private static final String ADMIN_USER = "pw-admin";
    private static final String PASSWORT = "Playwright-2026!";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "rootpagesplaywrightit");
    }

    @LocalServerPort int port;
    @Autowired MyUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ISettingsService settingsService;

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
        // Rollen OHNE "ROLE_"-Praefix: MyUserDetailsService stellt ihn selbst voran — aus
        // "ROLE_ROOT" wuerde "ROLE_ROLE_ROOT", und hasRole("ROOT") saehe nichts (403).
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

    // ------------------------------------------------------------------ 1. Menue-Diagnose

    @Test
    @DisplayName("Menue-Diagnose rendert die Tabelle mit Menuepunkten (Record-EL)")
    void menueDiagnoseZeigtZeilen() {
        anmelden(ROOT_USER);
        page.navigate(url("/menudiagnose.html"));
        page.waitForLoadState();

        assertTrue(page.url().contains("menudiagnose"), "umgeleitet nach " + page.url());
        Locator tabelle = page.locator("#fm\\:tabelle");
        assertTrue(tabelle.count() > 0, "Diagnose-Tabelle fehlt: " + page.content());
        String text = tabelle.innerText();
        assertTrue(text.contains("Root | Menüsteuerung"), "Diagnose-Tabelle ohne Menuepunkte: " + text);
        assertFalse(page.content().contains("PropertyNotFoundException"));
    }

    // ------------------------------------------------------------------ 2. Datenverwaltung

    @Test
    @DisplayName("Datenverwaltung: Typ-Auswahl zeigt die Tabelle")
    void datenverwaltungZeigtTabelleNachAuswahl() {
        anmelden(ROOT_USER);
        page.navigate(url("/rootentities.html"));
        page.waitForLoadState();
        assertTrue(page.url().contains("rootentities"), "umgeleitet nach " + page.url());
        assertEquals(0, page.locator("#listForm\\:entityTable").count(), "Tabelle darf vor der Auswahl fehlen");

        // PrimeFaces-SelectOneMenu: Label anklicken, dann den ersten echten Eintrag im Panel.
        page.click("#selectorForm\\:entityType_label");
        Locator eintraege = page.locator("#selectorForm\\:entityType_panel li.ui-selectonemenu-item");
        eintraege.first().waitFor();
        assertTrue(eintraege.count() > 1, "keine Entitaeten zur Auswahl");
        Locator gewaehlt = eintraege.nth(1);
        String erwartet = gewaehlt.innerText().trim();
        gewaehlt.click();

        Locator tabelle = page.locator("#listForm\\:entityTable");
        tabelle.waitFor();
        assertTrue(tabelle.isVisible(), "Tabelle erschien nach der Auswahl nicht");
        String kopf = page.locator("#listForm .ui-panel-title").first().innerText().trim();
        assertEquals(erwartet, kopf, "Panel zeigt nicht den gewaehlten Typ");
    }

    // ------------------------------------------------------------------ 3. Swagger

    @Test
    @DisplayName("Swagger fehlt im Menue, solange springdoc aus ist")
    void swaggerFehltImMenueWennSpringdocAus() {
        anmelden(ROOT_USER);
        page.navigate(url("/index.html"));
        page.waitForLoadState();

        assertEquals(0, page.locator("a[href*='swagger-ui']").count(),
                "Swagger-Link im Menue, obwohl springdoc.swagger-ui.enabled=false");
        // Gegenprobe: das Root-Menue ist da (sonst waere der Test trivial gruen).
        assertTrue(page.locator("a[href*='mandatemenu.html']").count() > 0, "Root-Menue fehlt");
    }

    // ------------------------------------------------------------------ 4. Anleitung

    @Test
    @DisplayName("Anleitung: kein Menuepunkt, aber Info-Knopf und direkt erreichbar")
    void anleitungOhneMenuepunktUeberInfoKnopf() {
        anmelden(ROOT_USER);
        page.navigate(url("/mandatemenu.html"));
        page.waitForLoadState();
        assertTrue(page.url().contains("mandatemenu"), "umgeleitet nach " + page.url() + ": " + page.title());

        assertEquals(0, page.locator("nav a[href*='menuesteuerung-anleitung'], .layout-menu a[href*='menuesteuerung-anleitung']").count(),
                "Anleitung haengt noch als Menuepunkt im Menue");
        Locator knopf = page.locator("#fm\\:anleitung");
        assertTrue(knopf.count() > 0, "Info-Knopf fehlt auf der Menuesteuerung");
        knopf.click();
        page.waitForLoadState();
        assertTrue(page.url().contains("menuesteuerung-anleitung"), "Info-Knopf fuehrt nicht zur Anleitung: " + page.url());
        assertTrue(page.content().contains("Anleitung"));
    }

    // ------------------------------------------------------------------ 6. Sprachwechsel + Setup

    @Test
    @DisplayName("Setup-Schalter Sprachwechsel blendet das Topbar-Symbol aus und wieder ein")
    void sprachwechselFolgtDemSetupSchalter() {
        anmelden(ROOT_USER);
        try {
            settingsService.setSetting("branding.i18n.enabled", "default", "false", "BOOLEAN", "IT");
            page.navigate(url("/index.html"));
            page.waitForLoadState();
            assertEquals(0, page.locator("#i18n-lang-button").count(),
                    "Sprachwechsel-Symbol trotz branding.i18n.enabled=false sichtbar");

            settingsService.setSetting("branding.i18n.enabled", "default", "true", "BOOLEAN", "IT");
            page.navigate(url("/index.html"));
            page.waitForLoadState();
            assertEquals(1, page.locator("#i18n-lang-button").count(),
                    "Sprachwechsel-Symbol fehlt trotz branding.i18n.enabled=true");
        } finally {
            settingsService.setSetting("branding.i18n.enabled", "default", "true", "BOOLEAN", "IT");
        }
    }

    @Test
    @DisplayName("Setup-Seite rendert mit den responsiven Zeilen")
    void setupSeiteRendert() {
        anmelden(ROOT_USER);
        page.navigate(url("/setup.html"));
        page.waitForLoadState();
        assertTrue(page.url().contains("setup"), "umgeleitet nach " + page.url());
        assertTrue(page.locator(".setup-grid").count() >= 6, "responsive Setup-Zeilen fehlen");
        assertTrue(page.locator("#fm\\:i18nEnabled").count() > 0, "Sprachwechsel-Schalter fehlt");
    }

    // ------------------------------------------------------------------ 5. Mailtexte

    @Test
    @DisplayName("Mailtexte: fuer ADMIN erreichbar und im Menue verlinkt")
    void mailtexteFuerAdmin() {
        anmelden(ADMIN_USER);
        page.navigate(url("/index.html"));
        page.waitForLoadState();
        assertTrue(page.locator("a[href*='mailtemplates.html']").count() > 0, "Mailtexte nicht im Admin-Menue");

        page.navigate(url("/mailtemplates.html"));
        page.waitForLoadState();
        assertTrue(page.url().contains("mailtemplates"), "ADMIN wurde umgeleitet nach " + page.url());
        assertTrue(page.content().contains("Mailtext-Overrides"), "Mailtexte-Seite nicht gerendert");
    }
}

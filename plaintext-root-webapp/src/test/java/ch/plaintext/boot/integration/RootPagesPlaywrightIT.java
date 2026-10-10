/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.testsupport.EmbeddedPg;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
import ch.plaintext.sidecars.entity.AuthZustand;
import ch.plaintext.sidecars.entity.Sidecar;
import ch.plaintext.sidecars.entity.SidecarQuelle;
import ch.plaintext.sidecars.entity.SpeicherAblage;
import ch.plaintext.sidecars.repository.SidecarRepository;
import ch.plaintext.sidecars.repository.SpeicherAblageRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assignment from Daniel, 29.08.2026: the root/admin pages in a real browser — exactly the four
 * findings from app.guild42.ch, so that they do not come back:
 *
 * <ol>
 *   <li>{@code menudiagnose.html} renders the table (record EL error),</li>
 *   <li>{@code rootentities.html}: the type selector shows the table (Ajax instead of {@code submit()}),</li>
 *   <li>"Swagger" is missing from the menu as long as springdoc is off,</li>
 *   <li>the menu control guide is reachable through the info button without a menu entry,</li>
 *   <li>mail texts are reachable for an ADMIN and linked in the menu.</li>
 * </ol>
 *
 * <p>Set up like {@link SelfServicePlaywrightIT}: embedded PostgreSQL, Chromium headless; without an
 * installed Chromium the class skips itself silently.</p>
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
    @Autowired SidecarRepository sidecarRepository;
    @Autowired SpeicherAblageRepository ablageRepository;

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
        // Roles WITHOUT the "ROLE_" prefix: MyUserDetailsService prepends it itself — out of
        // "ROLE_ROOT" would come "ROLE_ROLE_ROOT", and hasRole("ROOT") would see nothing (403).
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

    // ------------------------------------------------------------------ 1. menu diagnosis

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

    // ------------------------------------------------------------------ 2. data administration

    @Test
    @DisplayName("Datenverwaltung: Typ-Auswahl zeigt die Tabelle")
    void datenverwaltungZeigtTabelleNachAuswahl() {
        anmelden(ROOT_USER);
        page.navigate(url("/rootentities.html"));
        page.waitForLoadState();
        assertTrue(page.url().contains("rootentities"), "umgeleitet nach " + page.url());
        assertEquals(0, page.locator("#listForm\\:entityTable").count(), "Tabelle darf vor der Auswahl fehlen");

        // PrimeFaces SelectOneMenu: click the label, then the first real entry in the panel.
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
        // Counter-check: the root menu is there (otherwise the test would be trivially green).
        assertTrue(page.locator("a[href*='mandatemenu.html']").count() > 0, "Root-Menue fehlt");
    }

    // ------------------------------------------------------------------ 3b. Woche ab Montag (Karte 1421)

    @Test
    @DisplayName("Kalender beginnen in jeder Sprache am Montag (primefaces-fixes.js, Karte 1421)")
    void wocheBeginntAmMontag() {
        anmelden(ROOT_USER);
        page.navigate(url("/index.html"));
        page.waitForLoadState();

        assertEquals(Boolean.TRUE, page.evaluate("() => PrimeFaces.__ptMontag === true"),
                "Montag-Umschlag aus primefaces-fixes.js nicht aktiv");
        // Positivkontrolle: Die eingebaute Locale en_US steht roh auf Sonntag (0). Ohne den Umschlag
        // saehe jeder englische Kalender den Sonntag zuerst; sonst bewiese die 1 unten nichts.
        assertEquals(0, ((Number) page.evaluate("() => PrimeFaces.locales['en_US'].firstDayOfWeek")).intValue(),
                "Rohwert en_US ist nicht mehr Sonntag, die Probe sagt dann nichts");
        assertEquals(1, ((Number) page.evaluate("() => PrimeFaces.getLocaleSettings('en_US').firstDayOfWeek")).intValue(),
                "getLocaleSettings('en_US') liefert nicht Montag");
    }

    // ------------------------------------------------------------------ 4. guide

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

    // ------------------------------------------------------------------ 6. language switch + setup

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

    // ------------------------------------------------------------------ 5. mail texts

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

    // ------------------------------------------------------------------ 7. sidecar cockpit (Karte 1413)

    /**
     * Wartet, bis keine Ajax-Anfrage mehr läuft und keine Ein- oder Ausblendung mehr animiert wird.
     * In der CI ging ein Klick auf «Abbrechen» sonst in eine noch laufende Einblendung, und der
     * Dialog blieb über dem nächsten Knopf stehen.
     */
    private void ruhig() {
        page.waitForFunction("() => window.jQuery && jQuery.active === 0 && jQuery(':animated').length === 0"
                + " && (!window.PrimeFaces || PrimeFaces.ajax.Queue.isEmpty())");
    }

    @Test
    @DisplayName("Sidecars: Reiter mit Anzahl, Fehlerzeile offen mit Token-Feld, beide Dialoge gehen mit Feldern auf")
    void sidecarCockpit() {
        if (sidecarRepository.findFirstByNameAndDeletedFalse("pw-kaputt").isEmpty()) {
            Sidecar s = new Sidecar();
            s.setName("pw-kaputt");
            s.setMandat(Sidecar.MANDAT);
            s.setUrl("http://127.0.0.1:9");
            s.setQuelle(SidecarQuelle.HAND);
            s.setErreichbar(false);
            s.setFehler("Verbindung abgelehnt");
            s.setAuthZustand(AuthZustand.KEIN_TOKEN);
            // In der Zukunft: der Seitenaufruf fragt ihn dann nicht neu ab (FRISCH_SEKUNDEN), der Stand bleibt fest.
            s.setLetzteAbfrage(Instant.now().plusSeconds(3600));
            sidecarRepository.save(s);
        }
        if (ablageRepository.findFirstByNameAndDeletedFalse("pw-ablage").isEmpty()) {
            SpeicherAblage a = new SpeicherAblage();
            a.setName("pw-ablage");
            a.setMandat(Sidecar.MANDAT);
            a.setUrl("https://cloud.example.invalid");
            a.setBenutzer("pw");
            a.setPfad("Projekte/pw");
            a.setOk(false);
            a.setMeldung("pw-meldung: nicht erreichbar");
            ablageRepository.save(a);
        }

        // PrimeFaces liefert Fehler IN der Teilantwort mit HTTP 200 (siehe DialogeOeffnenPlaywrightIT).
        List<String> ajaxFehler = new ArrayList<>();
        page.onRequestFinished(anfrage -> {
            if (!"POST".equals(anfrage.method())) {
                return;
            }
            try {
                Response antwort = anfrage.response();
                String rumpf = antwort == null ? "" : antwort.text();
                if (rumpf.startsWith("<?xml") && (rumpf.contains("<error-name>") || rumpf.contains("Exception"))) {
                    ajaxFehler.add(rumpf.length() > 400 ? rumpf.substring(0, 400) : rumpf);
                }
            } catch (RuntimeException _) {
                // Rumpf nicht mehr abrufbar: kein Befund.
            }
        });

        anmelden(ROOT_USER);
        Response seite = page.navigate(url("/sidecars.html"));
        page.waitForLoadState();
        assertTrue(page.url().contains("sidecars"), "umgeleitet nach " + page.url());
        assertEquals(200, seite.status(), "sidecars.html antwortet nicht mit 200");

        Locator reiter = page.locator("#fm\\:reiter .ui-tabs-header");
        assertEquals(2, reiter.count(), "zwei Reiter erwartet");
        assertTrue(reiter.nth(0).innerText().matches("Sidecars \\(\\d+\\)"), "Reiter 1: " + reiter.nth(0).innerText());
        assertTrue(reiter.nth(1).innerText().matches("Speicher-Ablagen \\(\\d+\\)"), "Reiter 2: " + reiter.nth(1).innerText());

        // Die Fehlerzeile steht offen: Fehlertext und Token-Feld sind ohne Klick sichtbar.
        Locator tabelle = page.locator("#fm\\:reiter\\:sidecars");
        Locator offen = tabelle.locator("tr[data-rk='pw-kaputt'] + tr.ui-expanded-row-content");
        assertEquals(1, offen.count(), "Fehlerzeile ist nicht aufgeklappt: " + tabelle.innerText());
        Locator fehlerText = offen.locator(".sc-fehler");
        assertTrue(fehlerText.isVisible() && !fehlerText.innerText().isBlank(),
                "Fehlertext fehlt in der offenen Zeile: " + offen.innerText());
        Locator tokenFeld = offen.locator("input[type=password]");
        assertTrue(tokenFeld.isVisible(), "Token-Feld in der offenen Zeile fehlt");
        assertEquals("", tokenFeld.inputValue(), "Token-Feld darf nie vorbelegt sein");
        assertTrue(tabelle.locator("tr[data-rk='pw-kaputt']").innerText().contains("unbekannt"), "Spalte Zugang zeigt den Zustand nicht");

        // Zuklappen und wieder aufklappen über den rowToggler (Ajax-Nachladen der Zeile).
        Locator toggler = tabelle.locator("tr[data-rk='pw-kaputt'] .ui-row-toggler");
        toggler.click();
        page.waitForCondition(() -> !tabelle.locator("tr[data-rk='pw-kaputt']").getAttribute("class").contains("ui-expanded-row"));
        toggler.click();
        tabelle.locator("tr[data-rk='pw-kaputt'] + tr.ui-expanded-row-content input[type=password]").waitFor();

        // Dialog «Sidecar ergänzen»
        page.waitForResponse(r -> "POST".equals(r.request().method()), () -> page.click("#fm\\:reiter\\:ergaenzen"));
        Locator dlgErgaenzen = page.locator("#fm\\:dlgErgaenzen");
        dlgErgaenzen.waitFor();
        ruhig();
        assertTrue(page.locator("#fm\\:neueUrl").isVisible(), "Dialog «Sidecar ergänzen» ohne URL-Feld");
        page.fill("#fm\\:neueUrl", "ftp://nicht-erlaubt");
        page.waitForResponse(r -> "POST".equals(r.request().method()), () -> page.click("#fm\\:ergaenzenSpeichern"));
        page.locator(".ui-growl-message").first().waitFor();
        ruhig();
        assertTrue(dlgErgaenzen.isVisible(), "Dialog muss bei abgelehnter URL offen bleiben");
        dlgErgaenzen.locator("button:has-text('Abbrechen')").click();
        dlgErgaenzen.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        // Reiter «Speicher-Ablagen»: Fehlerzeile offen, «Ablage einrichten» öffnet den Dialog
        reiter.nth(1).click();
        Locator ablagen = page.locator("#fm\\:reiter\\:ablagen");
        ablagen.waitFor();
        Locator ablageOffen = ablagen.locator("tr[data-rk='pw-ablage'] + tr.ui-expanded-row-content");
        assertEquals(1, ablageOffen.count(), "Ablage mit Fehler ist nicht aufgeklappt: " + ablagen.innerText());
        assertTrue(ablageOffen.innerText().contains("pw-meldung"), "Meldung fehlt: " + ablageOffen.innerText());
        page.waitForResponse(r -> "POST".equals(r.request().method()), () -> page.click("#fm\\:reiter\\:ablageEinrichten"));
        Locator dlgAblage = page.locator("#fm\\:dlgAblage");
        dlgAblage.waitFor();
        ruhig();
        for (String feld : List.of("abName", "abUrl", "abBenutzer", "abPasswort", "abPfad")) {
            assertTrue(page.locator("#fm\\:" + feld).isVisible(), "Feld " + feld + " fehlt im Ablage-Dialog");
        }
        assertTrue(dlgAblage.innerText().contains("Ablage einrichten"), "Kopf des Ablage-Dialogs: " + dlgAblage.innerText());
        dlgAblage.locator("button:has-text('Abbrechen')").click();
        dlgAblage.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        // «Ändern» füllt den Dialog vor, der Name ist fest, das Passwort leer.
        ablageOffen.locator("button:has-text('Ändern')").click();
        page.waitForCondition(() -> "pw-ablage".equals(page.locator("#fm\\:abName").inputValue()));
        assertTrue(dlgAblage.isVisible(), "Ablage-Dialog ging für «Ändern» nicht auf");
        assertTrue(dlgAblage.innerText().contains("Ablage ändern"), "Kopf beim Ändern: " + dlgAblage.innerText());
        assertEquals("", page.locator("#fm\\:abPasswort").inputValue(), "Passwort darf nie vorbelegt sein");

        assertTrue(ajaxFehler.isEmpty(), "Fehler in Teilantworten: " + ajaxFehler);
        assertFalse(page.content().contains("PropertyNotFoundException"));
    }

    @Test
    @DisplayName("Karte 1422: Modulseite zeigt die Schnittstellen, Suche, Aufklappen mit Methoden, Filter DTO als Gegenprobe")
    void modulSchnittstellen() {
        List<String> ajaxFehler = new ArrayList<>();
        page.onRequestFinished(anfrage -> {
            if (!"POST".equals(anfrage.method())) {
                return;
            }
            try {
                Response antwort = anfrage.response();
                String rumpf = antwort == null ? "" : antwort.text();
                if (rumpf.startsWith("<?xml") && (rumpf.contains("<error-name>") || rumpf.contains("Exception"))) {
                    ajaxFehler.add(rumpf.length() > 400 ? rumpf.substring(0, 400) : rumpf);
                }
            } catch (RuntimeException _) {
                // Rumpf nicht mehr abrufbar: kein Befund.
            }
        });
        anmelden(ROOT_USER);
        Response seite = page.navigate(url("/module.html"));
        page.waitForLoadState();
        assertEquals(200, seite.status(), "module.html: HTTP " + seite.status());
        Locator tabelle = page.locator("#fm\\:schnittstellen");
        assertTrue(tabelle.count() > 0, "Schnittstellen-Tabelle fehlt");
        assertTrue(tabelle.locator("tbody tr").count() >= 20,
                "zu wenige Schnittstellen (plaintext-root-interfaces hat ueber 30): " + tabelle.innerText());

        page.locator("#fm\\:schnittstellenSuche").fill("secretresolver");
        page.locator("#fm\\:schnittstellenSuche").press("End");   // keyup loest die Suche aus
        page.waitForFunction("() => document.querySelectorAll('[id=\"fm:schnittstellen_data\"] > tr').length === 1");
        ruhig();
        assertTrue(tabelle.innerText().contains("SecretResolver"), "Suche findet SecretResolver nicht: " + tabelle.innerText());

        tabelle.locator(".ui-row-toggler").first().click();
        page.waitForSelector(".ui-expanded-row-content");
        ruhig();
        String details = page.locator(".ui-expanded-row-content").first().innerText();
        assertTrue(details.contains("Methoden") && details.contains("resolve"), "Aufgeklappt ohne Methoden: " + details);
        assertTrue(details.contains("Genutzt von") && details.contains("Umsetzer"), "Aufgeklappt ohne Nutzer/Umsetzer: " + details);

        // Filter DTO: genau die markierten DTOs aus root-interfaces (Etappe B: zwei; Karte 1476: FreigabeZugriff und FreigabeInhalt; das Enum FreigabeRecht fuehrt der Katalog nicht)
        page.locator("#fm\\:schnittstellenSuche").fill("");
        page.locator("#fm\\:schnittstellenSuche").press("End");
        page.waitForFunction("() => document.querySelectorAll('[id=\"fm:schnittstellen_data\"] > tr').length >= 20");
        ruhig();
        page.locator("#fm\\:schnittstellenArt").getByText("DTOs").click();
        page.waitForFunction("() => document.querySelectorAll('[id=\"fm:schnittstellen_data\"] > tr').length === 4");
        ruhig();
        String dtos = tabelle.innerText();
        assertTrue(dtos.contains("IApiErrorResponse") && dtos.contains("ITokenValidationOutcome")
                && dtos.contains("FreigabeZugriff") && dtos.contains("FreigabeInhalt"), "DTO-Filter: " + dtos);
        // Gegenprobe: ein Suchbegriff ohne Treffer leert die Liste
        page.locator("#fm\\:schnittstellenSuche").fill("gibtesnirgends");
        page.locator("#fm\\:schnittstellenSuche").press("End");
        page.waitForFunction("() => document.querySelector('[id=\"fm:schnittstellen\"]').innerText.includes('Keine Schnittstelle passt zur Suche.')");
        assertEquals(List.of(), ajaxFehler, "Fehler in den Ajax-Antworten");
        assertFalse(page.content().contains("PropertyNotFoundException"));
    }

    @Test
    @DisplayName("Karte 1438: Modul aufklappen zeigt bietet an/setzt um/nutzt mit Badge root; Filter Module leert beide Listen, root füllt sie wieder")
    void modulAufklappenUndEbene() {
        List<String> ajaxFehler = new ArrayList<>();
        page.onRequestFinished(anfrage -> {
            if (!"POST".equals(anfrage.method())) {
                return;
            }
            try {
                Response antwort = anfrage.response();
                String rumpf = antwort == null ? "" : antwort.text();
                if (rumpf.startsWith("<?xml") && (rumpf.contains("<error-name>") || rumpf.contains("Exception"))) {
                    ajaxFehler.add(rumpf.length() > 400 ? rumpf.substring(0, 400) : rumpf);
                }
            } catch (RuntimeException _) {
                // Rumpf nicht mehr abrufbar: kein Befund.
            }
        });
        anmelden(ROOT_USER);
        Response seite = page.navigate(url("/module.html"));
        page.waitForLoadState();
        assertEquals(200, seite.status(), "module.html: HTTP " + seite.status());
        // nur Datenzeilen: eine aufgeklappte Zeile bringt ein weiteres tr ohne data-ri, und PrimeFaces
        // haelt sie ueber rowKey auch nach dem Filtern offen
        String zeilen = "[id=\"fm:tbl_data\"] > tr[data-ri]";
        int module = page.locator(zeilen).count();
        assertTrue(module >= 5, "zu wenige Module in root: " + module);

        // Secrets (plaintext-admin-secrets) setzt SecretResolver aus plaintext-root-interfaces um
        Locator secrets = page.locator(zeilen).filter(new Locator.FilterOptions().setHasText("plaintext-admin-secrets"));
        assertEquals(1, secrets.count(), "Zeile Secrets fehlt: " + page.locator("#fm\\:tbl").innerText());
        assertTrue(secrets.innerText().contains("root"), "Badge root fehlt an der Modulzeile: " + secrets.innerText());
        secrets.locator(".ui-row-toggler").click();
        page.waitForSelector("[id=\"fm:tbl\"] .ui-expanded-row-content");
        ruhig();
        String bild = page.locator("[id=\"fm:tbl\"] .ui-expanded-row-content").first().innerText();
        assertTrue(bild.contains("Bietet an") && bild.contains("Setzt um") && bild.contains("Nutzt"), "Listen fehlen: " + bild);
        assertTrue(bild.contains("SecretResolver"), "SecretResolver fehlt unter «Setzt um»: " + bild);
        assertTrue(bild.contains("root"), "Badge root fehlt im aufgeklappten Modul: " + bild);

        // Gegenprobe: in root gibt es keine Fachmodule, «Module» leert beide Listen
        page.locator("#fm\\:ebene").getByText("Module").click();
        page.waitForFunction("() => document.querySelector('[id=\"fm:schnittstellen\"]').innerText.includes('Keine Schnittstelle passt zur Suche.')");
        ruhig();
        assertTrue(page.locator("#fm\\:tbl").innerText().contains("Keine Module gemeldet"),
                "Filter Module zeigt root-Module: " + page.locator("#fm\\:tbl").innerText());

        page.locator("#fm\\:ebene").getByText("root").click();
        page.waitForFunction("n => document.querySelectorAll('[id=\"fm:tbl_data\"] > tr[data-ri]').length === n", module);
        ruhig();
        assertTrue(page.locator("[id=\"fm:schnittstellen_data\"] > tr").count() >= 20, "Filter root leert die Schnittstellen");
        assertEquals(List.of(), ajaxFehler, "Fehler in den Ajax-Antworten");
        assertFalse(page.content().contains("PropertyNotFoundException"));
    }
}

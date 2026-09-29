/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.jsf.userprofile.UserPreference;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPrefsSimpleStorage;
import ch.plaintext.boot.plugins.objstore.SimpleStorableEntityRepository;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.table.TableState;
import ch.plaintext.testsupport.EmbeddedPg;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1336, Pruefung 6: der Tabellenstand ({@code TableState}) gehoert der Person — im
 * Browser, mit zwei Benutzern und zwei Sitzungen fuer denselben Benutzer.
 *
 * <p><b>Warum die Benutzerverwaltung.</b> Die einzige Seite in root, die ihren Tabellenstand
 * ueber {@code TableSettings} und die mitgelieferte Ablage ({@code UserPreferenceTableStateStore})
 * haelt, ist {@code useradmin.xhtml} — dort ueber das Spaltenmenue. Das Tag
 * {@code pt:tableSettings} rendert in root keine Seite; sein erster Abnehmer ist member.xhtml in
 * guild. Die Ablage ist fuer beide dieselbe: der ganze Stand als JSON in {@code UserPreference}, je
 * Benutzer und Mandant. Was hier fuer die Spaltenwahl gilt, gilt deshalb fuer jedes Feld des
 * Stands; dass Paginator und Seitengroesse (Karte 1336) den Weg durch das JSON ueberstehen, zeigt
 * {@code TabellenStandZweiBenutzerTest} in plaintext-root-common.</p>
 *
 * <p><b>Ablauf.</b> A blendet die Spalte "ID" aus. B meldet sich an und sieht sie. A meldet sich in
 * einem neuen Browser-Kontext (neue Session, neues Session-Bean) wieder an und sieht sie nicht —
 * dazwischen der Blick in die Datenbank, dass der Stand wirklich bei A liegt und nicht nur in der
 * Session.</p>
 *
 * <p>Aufgebaut wie {@link RootPagesPlaywrightIT}: eingebettetes PostgreSQL, Chromium headless;
 * ohne installiertes Chromium ueberspringt sich die Klasse.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.docker.compose.enabled=false",
                "plaintext.cron.default-enabled=false",
                "plaintext.cron.default-startup=false"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TabellenstandZweiBenutzerPlaywrightIT {

    private static final String ANNA = "pw-tabelle-anna";
    private static final String BRUNO = "pw-tabelle-bruno";
    private static final String PASSWORT = "Playwright-2026!";

    /** Ablageschluessel: Mandant + "/" + Seitenschluessel von MyUserBackingBean. */
    private static final String SCHLUESSEL = "default/useradmin";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "tabellenstandzweibenutzerplaywrightit");
    }

    @LocalServerPort int port;
    @Autowired MyUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserPrefsSimpleStorage userPrefs;
    @Autowired SimpleStorableEntityRepository ablage;

    private Playwright playwright;
    private Browser browser;

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
        // Rollen OHNE "ROLE_"-Praefix: MyUserDetailsService setzt es selbst davor.
        benutzerAnlegen(ANNA);
        benutzerAnlegen(BRUNO);
    }

    private void benutzerAnlegen(String name) {
        if (userRepository.findByUsername(name) != null) {
            return;
        }
        MyUserEntity user = new MyUserEntity();
        user.setUsername(name);
        user.setPassword(passwordEncoder.encode(PASSWORT));
        user.addRole("ADMIN");
        user.addRole("USER");
        user.setMandat("default");
        userRepository.save(user);
    }

    @AfterAll
    void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Test
    @DisplayName("A blendet eine Spalte aus: B sieht sie weiter, A nach neuer Anmeldung nicht")
    void spaltenwahlGehoertDerPerson() {
        // ── A: Vorgabe sehen (Positivkontrolle), dann "ID" ausblenden ──────
        try (BrowserContext a = browser.newContext()) {
            Page seite = benutzerverwaltung(a, ANNA);
            assertTrue(idKopf(seite).isVisible(), "Vorgabe: die Spalte ID muss zu Beginn sichtbar sein");

            seite.click("#fm\\:spalten");
            Locator eintragId = seite.locator("#fm\\:spalten_panel li.ui-selectcheckboxmenu-item")
                    .filter(new Locator.FilterOptions().setHasText(Pattern.compile("^\\s*ID\\s*$")));
            eintragId.locator(".ui-chkbox-box").click();
            seite.keyboard().press("Escape");

            TableState gespeichert = warteAufGespeichertenStand(ANNA);
            assertEquals(Boolean.FALSE, gespeichert.getColumnVisible().get("id"),
                    "In der Datenbank steht fuer A nicht 'id=false': " + gespeichert.getColumnVisible());
            idKopf(seite).waitFor(new Locator.WaitForOptions()
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));
        }
        // Der Befund vom 29.09.2026: das Session-Bean entstand auf der Anmeldeseite und schrieb
        // danach fuer jeden Benutzer in den gemeinsamen Datensatz "anonymousUser".
        assertFalse(eintraegeInDerAblage().contains("anonymousUser"),
                "Es gibt einen gemeinsamen Datensatz anonymousUser: " + eintraegeInDerAblage());

        // ── B: eigene Sitzung, eigener Stand — die Spalte ist da ───────────
        try (BrowserContext b = browser.newContext()) {
            Page seite = benutzerverwaltung(b, BRUNO);
            assertTrue(idKopf(seite).isVisible(), "B sieht die Einstellung von A: Spalte ID fehlt");
        }
        UserPreference beiB = userPrefs.findByUniqueId(BRUNO);
        assertNotNull(beiB, "B hat nach dem Seitenaufruf gar keine Einstellungen");
        TableState standB = beiB.getTabellenStaende().get(SCHLUESSEL);
        assertTrue(standB == null || !Boolean.FALSE.equals(standB.getColumnVisible().get("id")),
                "Der Stand von B enthaelt die Abwahl von A: " + standB);

        // ── A: neue Sitzung (Abmelden/Neuladen) — die Abwahl ist geblieben ─
        try (BrowserContext aNeu = browser.newContext()) {
            Page seite = benutzerverwaltung(aNeu, ANNA);
            // Gegenkontrolle, dass die Tabelle ueberhaupt da ist: die Spalte Benutzername.
            assertTrue(seite.locator("#fm\\:tbl thead th .ui-column-title:text-is('Benutzername')").isVisible(),
                    "Tabelle der Benutzerverwaltung nicht gerendert");
            assertFalse(idKopf(seite).isVisible(), "A hat nach neuer Anmeldung die Spalte ID wieder");
        }
    }

    private Page benutzerverwaltung(BrowserContext kontext, String benutzer) {
        Page seite = kontext.newPage();
        seite.navigate(url("/login.html"));
        seite.fill("#username", benutzer);
        seite.fill("#password", PASSWORT);
        seite.locator("#password").press("Enter");
        seite.waitForURL(u -> !u.contains("login"), new Page.WaitForURLOptions().setTimeout(90_000));
        seite.navigate(url("/useradmin.html"));
        seite.waitForLoadState();
        assertTrue(seite.url().contains("useradmin"), "umgeleitet nach " + seite.url());
        seite.locator("#fm\\:tbl").waitFor();
        return seite;
    }

    /** Kopfzelle der Spalte "ID" — nur im Tabellenkopf, reflow wiederholt die Titel in jeder Zeile. */
    private static Locator idKopf(Page seite) {
        return seite.locator("#fm\\:tbl thead th:has(.ui-column-title:text-is('ID'))");
    }

    /** Das Speichern laeuft per Ajax; bis zu 15 s warten, bis der Stand in der Datenbank steht. */
    private TableState warteAufGespeichertenStand(String benutzer) {
        long bis = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < bis) {
            UserPreference prefs = userPrefs.findByUniqueId(benutzer);
            TableState stand = prefs == null ? null : prefs.getTabellenStaende().get(SCHLUESSEL);
            if (stand != null && Boolean.FALSE.equals(stand.getColumnVisible().get("id"))) {
                return stand;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        UserPreference prefs = userPrefs.findByUniqueId(benutzer);
        String vorhanden = eintraegeInDerAblage().toString();
        throw new AssertionError("Kein Stand mit id=false fuer " + benutzer + " unter " + SCHLUESSEL
                + " — gefunden: " + (prefs == null ? "keine Einstellungen" : prefs.getTabellenStaende())
                + " — Eintraege in der Ablage: " + vorhanden);
    }

    /** Die Benutzer, fuer die Einstellungen in der Ablage liegen. */
    private java.util.List<String> eintraegeInDerAblage() {
        return ablage.findAll().stream()
                .map(e -> e.getMyObject() == null ? "(leer)" : e.getMyObject().getUniqueId())
                .toList();
    }
}

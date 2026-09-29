/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.jsf.userprofile.UserPreference;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPrefsSimpleStorage;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.table.TableSort;
import ch.plaintext.boot.table.TableState;
import ch.plaintext.testsupport.EmbeddedPg;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1346: die Sortierung einer Tabelle gehoert der Person — im Browser, mit zwei Benutzern
 * und einer neuen Sitzung fuer denselben Benutzer. Aufgebaut wie
 * {@link TabellenstandZweiBenutzerPlaywrightIT} (Karte 1336), auf derselben Seite.
 *
 * <p><b>Warum die Benutzerverwaltung.</b> {@code useradmin.xhtml} ist die einzige Seite in root,
 * die ihren Stand ueber {@code TableSettings} haelt, und bindet seit Karte 1346
 * {@code sortBy="#{myUserBackingBean.anzeige.sortMeta}"} und {@code <p:ajax event="sort">} — genau
 * so, wie es member.xhtml in guild tun wird. Die Tabelle hat {@code multiViewState="true"}: innerhalb
 * einer Sitzung haelt PrimeFaces die Sortierung ohnehin. Jeder Schritt hier laeuft deshalb in einem
 * neuen Browser-Kontext (neue Sitzung); was dort sortiert ankommt, kann nur aus dem gespeicherten
 * Stand kommen.</p>
 *
 * <p><b>Ablauf.</b> A sortiert "Nachname" aufsteigend. B sieht keine Sortierung. A sieht in einer
 * neuen Sitzung "Nachname" aufsteigend und die Zeilen in dieser Reihenfolge. Zuletzt ein veralteter
 * Stand (ein Feld, das die Tabelle nicht mehr hat): die Seite muss laden — PrimeFaces wirft sonst
 * {@code FacesException: No column with field ...} — und der gueltige zweite Eintrag muss wirken.</p>
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
class TabellenSortierungZweiBenutzerPlaywrightIT {

    private static final String ANNA = "pw-sort-anna";
    private static final String BRUNO = "pw-sort-bruno";
    private static final String PASSWORT = "Playwright-2026!";

    /** Nachnamen so gewaehlt, dass "aufsteigend" die Anlage-Reihenfolge umdreht. */
    private static final String NACHNAME_ANNA = "Zeller-Sortiertest";
    private static final String NACHNAME_BRUNO = "Amsler-Sortiertest";

    /** Ablageschluessel: Mandant + "/" + Seitenschluessel von MyUserBackingBean. */
    private static final String SCHLUESSEL = "default/useradmin";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "tabellensortierungzweibenutzerplaywrightit");
    }

    @LocalServerPort int port;
    @Autowired MyUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserPrefsSimpleStorage userPrefs;

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
        benutzerAnlegen(ANNA, NACHNAME_ANNA);
        benutzerAnlegen(BRUNO, NACHNAME_BRUNO);
    }

    private void benutzerAnlegen(String name, String nachname) {
        if (userRepository.findByUsername(name) != null) {
            return;
        }
        MyUserEntity user = new MyUserEntity();
        user.setUsername(name);
        user.setPassword(passwordEncoder.encode(PASSWORT));
        user.setVorname(name);
        user.setNachname(nachname);
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
    @DisplayName("A sortiert: B sieht keine Sortierung, A nach neuer Anmeldung wieder; ein veralteter Stand sperrt die Seite nicht")
    void sortierungGehoertDerPerson() {
        // ── A: Ausgangslage unsortiert (Positivkontrolle), dann "Nachname" aufsteigend ──
        try (BrowserContext a = browser.newContext()) {
            Page seite = benutzerverwaltung(a, ANNA);
            assertFalse(sortiert(seite, "Nachname"), "Vorgabe: Nachname darf zu Beginn nicht sortiert sein");

            kopf(seite, "Nachname").locator(".ui-column-title").click();
            seite.locator("#fm\\:tbl thead th.ui-state-active:has(.ui-column-title:text-is('Nachname'))").waitFor();

            TableState gespeichert = warteAufSortierung(ANNA,
                    List.of(new TableSort("nachname", "nachname", false)));
            assertEquals(List.of(new TableSort("nachname", "nachname", false)), gespeichert.getSortBy());
        }

        // ── B: eigene Sitzung, eigener Stand — keine Sortierung ────────────
        try (BrowserContext b = browser.newContext()) {
            Page seite = benutzerverwaltung(b, BRUNO);
            assertTrue(kopf(seite, "Benutzername").isVisible(), "Tabelle der Benutzerverwaltung nicht gerendert");
            assertFalse(sortiert(seite, "Nachname"), "B sieht die Sortierung von A");
            assertEquals(0, seite.locator("#fm\\:tbl thead th.ui-state-active").count(),
                    "B hat eine sortierte Spalte, obwohl B nie sortiert hat");
        }
        UserPreference beiB = userPrefs.findByUniqueId(BRUNO);
        TableState standB = beiB == null ? null : beiB.getTabellenStaende().get(SCHLUESSEL);
        assertTrue(standB == null || standB.getSortBy() == null,
                "Der Stand von B enthaelt eine Sortierung: " + standB);

        // ── A: neue Sitzung — "Nachname" aufsteigend, Zeilen in dieser Reihenfolge ──
        try (BrowserContext aNeu = browser.newContext()) {
            Page seite = benutzerverwaltung(aNeu, ANNA);
            assertTrue(sortiert(seite, "Nachname"), "A hat nach neuer Anmeldung keine Sortierung mehr");
            assertTrue(kopf(seite, "Nachname").locator(".ui-icon-triangle-1-n").count() > 0,
                    "Nachname ist sortiert, aber nicht aufsteigend");
            List<String> nachnamen = nachnamen(seite);
            int anna = nachnamen.indexOf(NACHNAME_ANNA);
            int bruno = nachnamen.indexOf(NACHNAME_BRUNO);
            assertTrue(anna >= 0 && bruno >= 0, "Testbenutzer fehlen in der Tabelle: " + nachnamen);
            assertTrue(bruno < anna, "Nicht nach Nachname aufsteigend sortiert: " + nachnamen);
        }

        // ── Veralteter Stand: ein Feld, das die Tabelle nicht (mehr) hat ─────
        // Ohne die Pruefung in TableSettings.getSortMeta antwortet die Seite hier mit HTTP 500.
        UserPreference prefs = userPrefs.findByUniqueId(ANNA);
        assertNotNull(prefs);
        TableState stand = prefs.getTabellenStaende().get(SCHLUESSEL);
        stand.setSortBy(new ArrayList<>(List.of(
                new TableSort("nachname", "gibtEsNichtMehr", false),
                new TableSort("vorname", "vorname", true))));
        userPrefs.save(prefs);

        try (BrowserContext aAlt = browser.newContext()) {
            Page seite = benutzerverwaltung(aAlt, ANNA);
            assertFalse(sortiert(seite, "Nachname"), "Der veraltete Eintrag hat trotzdem sortiert");
            // Positivkontrolle: der gueltige Eintrag dahinter wirkt — die Pruefung laesst nicht einfach alles weg.
            assertTrue(sortiert(seite, "Vorname"), "Der gueltige Eintrag (Vorname absteigend) fehlt");
            assertTrue(kopf(seite, "Vorname").locator(".ui-icon-triangle-1-s").count() > 0,
                    "Vorname ist sortiert, aber nicht absteigend");
        }
    }

    private Page benutzerverwaltung(BrowserContext kontext, String benutzer) {
        Page seite = kontext.newPage();
        seite.navigate(url("/login.html"));
        seite.fill("#username", benutzer);
        seite.fill("#password", PASSWORT);
        seite.locator("#password").press("Enter");
        seite.waitForURL(u -> !u.contains("login"), new Page.WaitForURLOptions().setTimeout(90_000));
        Response antwort = seite.navigate(url("/useradmin.html"));
        seite.waitForLoadState();
        assertNotNull(antwort, "keine Antwort auf useradmin.html");
        assertEquals(200, antwort.status(), "useradmin.html antwortet mit HTTP " + antwort.status());
        assertTrue(seite.url().contains("useradmin"), "umgeleitet nach " + seite.url());
        seite.locator("#fm\\:tbl").waitFor();
        return seite;
    }

    /** Kopfzelle einer Spalte — nur im Tabellenkopf, reflow wiederholt die Titel in jeder Zeile. */
    private static Locator kopf(Page seite, String titel) {
        return seite.locator("#fm\\:tbl thead th:has(.ui-column-title:text-is('" + titel + "'))");
    }

    /** Markiert PrimeFaces die Spalte im Kopf als sortiert (Klasse {@code ui-state-active})? */
    private static boolean sortiert(Page seite, String titel) {
        Locator th = kopf(seite, titel);
        th.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
        String klasse = th.getAttribute("class");
        return klasse != null && klasse.contains("ui-state-active");
    }

    /** Die Nachnamen in Zeilenreihenfolge (4. Spalte; reflow stellt jeder Zelle den Titel voran). */
    @SuppressWarnings("unchecked")
    private static List<String> nachnamen(Page seite) {
        return (List<String>) seite.evaluate("() => Array.from(document.querySelectorAll('[id=\"fm:tbl_data\"] > tr'))"
                + ".map(tr => tr.children[3] ? tr.children[3].lastChild.textContent.trim() : '')");
    }

    /** Das Speichern laeuft per Ajax; bis zu 15 s warten, bis die Sortierung in der Datenbank steht. */
    private TableState warteAufSortierung(String benutzer, List<TableSort> erwartet) {
        long bis = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < bis) {
            UserPreference prefs = userPrefs.findByUniqueId(benutzer);
            TableState stand = prefs == null ? null : prefs.getTabellenStaende().get(SCHLUESSEL);
            if (stand != null && erwartet.equals(stand.getSortBy())) {
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
        throw new AssertionError("Keine Sortierung " + erwartet + " fuer " + benutzer + " unter " + SCHLUESSEL
                + " — gefunden: " + (prefs == null ? "keine Einstellungen" : prefs.getTabellenStaende()));
    }
}

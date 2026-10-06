/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.jsf.userprofile.UserPreference;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPrefsSimpleStorage;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.startseite.StartseitenLayout;
import ch.plaintext.testsupport.EmbeddedPg;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1351: der Seitendurchgang der Abnahme im Browser — Edit-Modus oeffnen, eine Kachel
 * ausblenden, eine per Drag and Drop verschieben, die Breite umstellen, speichern, neu anmelden:
 * der Zustand bleibt. Dazu zwei halbe Kacheln nebeneinander und auf schmaler Ansicht untereinander,
 * "Standard wiederherstellen", und die Rechte mit Positiv- und Negativkontrolle.
 *
 * <p><b>Kacheln.</b> root selbst hat keine Startseiten-Kachel. Der Test setzt
 * {@code plaintext.dashboard.scan-package} auf das Testpaket {@code startseitetest}: vier Kacheln
 * fuer USER/ADMIN/ROOT (A–D) und eine nur fuer ROOT. Das Paket liegt ausserhalb von
 * {@code ch.plaintext}, damit keine andere Klasse sie zu sehen bekommt.</p>
 *
 * <p>Aufgebaut wie {@link TabellenstandZweiBenutzerPlaywrightIT}: eingebettetes PostgreSQL,
 * Chromium headless; ohne installiertes Chromium ueberspringt sich die Klasse.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.docker.compose.enabled=false",
                "plaintext.cron.default-enabled=false",
                "plaintext.cron.default-startup=false",
                "plaintext.dashboard.scan-package=startseitetest"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StartseiteAnpassenPlaywrightIT {

    private static final String ANNA = "pw-start-anna";
    private static final String ROOT = "pw-start-root";
    private static final String PASSWORT = "Playwright-2026!";

    /** Ablageschluessel der Startseite: der Mandant. */
    private static final String MANDAT = "default";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "startseiteanpassenplaywrightit");
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
        // Rollen OHNE "ROLE_"-Praefix: MyUserDetailsService setzt es selbst davor.
        benutzerAnlegen(ANNA, "ADMIN", "USER");
        benutzerAnlegen(ROOT, "ROOT", "ADMIN", "USER");
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
        user.setMandat(MANDAT);
        userRepository.save(user);
    }

    @AfterAll
    void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Test
    @Order(1)
    @DisplayName("Ausblenden, Ziehen, Breite, Speichern: der Zustand uebersteht eine neue Anmeldung")
    void anpassenSpeichernNeuLaden() {
        try (BrowserContext kontext = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 1000))) {
            Page seite = startseite(kontext, ANNA);

            // Vorgabe (Positivkontrolle der Anzeige): A–D, keine Bedienelemente ausser "Anpassen".
            assertEquals(List.of("pw-a", "pw-b", "pw-c", "pw-d"), reihenfolge(seite));
            assertEquals(0, seite.locator(".dashboard-edit").count(), "Bedienelemente ausserhalb des Edit-Modus");
            assertTrue(seite.locator("#fm\\:anpassen").isVisible());

            anpassen(seite);
            assertEquals(4, seite.locator(".dashboard-edit").count());

            // B ausblenden
            zelle(seite, "pw-b").locator(".dashboard-schalter-sichtbar").uncheck();
            // D per Drag and Drop vor A ziehen (linke Haelfte der halben Kachel A = davor)
            zelle(seite, "pw-d").locator(".dashboard-griff").dragTo(zelle(seite, "pw-a"),
                    new Locator.DragToOptions().setTargetPosition(10, 10));
            // C auf volle Breite
            zelle(seite, "pw-c").locator(".dashboard-schalter-halb").uncheck();

            assertEquals(List.of("pw-d", "pw-a", "pw-b", "pw-c"), reihenfolge(seite),
                    "Drag and Drop hat D nicht vor A gesetzt");

            speichern(seite);
            assertEquals(List.of("pw-d", "pw-a", "pw-c"), reihenfolge(seite), "nach dem Speichern");
        }

        // In der Datenbank, nicht nur in der Sitzung:
        StartseitenLayout gespeichert = warteAufLayout(l -> l.getEintraege().size() == 4);
        assertEquals(List.of(
                new StartseitenLayout.Eintrag("pw-d", true, true),
                new StartseitenLayout.Eintrag("pw-a", true, true),
                new StartseitenLayout.Eintrag("pw-b", false, true),
                new StartseitenLayout.Eintrag("pw-c", true, false)), gespeichert.getEintraege());

        // Neue Sitzung (Abmelden/Neuladen): alles wie eingerichtet.
        try (BrowserContext kontext = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 1000))) {
            Page seite = startseite(kontext, ANNA);
            assertEquals(List.of("pw-d", "pw-a", "pw-c"), reihenfolge(seite));
            assertEquals(0, zelle(seite, "pw-b").count(), "ausgeblendete Kachel B ist wieder da");
            assertTrue(zelle(seite, "pw-c").getAttribute("class").contains("dashboard-voll"));

            // Zwei halbe Kacheln (D, A) stehen nebeneinander, C darunter ueber die ganze Breite.
            BoundingBox d = zelle(seite, "pw-d").boundingBox();
            BoundingBox a = zelle(seite, "pw-a").boundingBox();
            BoundingBox c = zelle(seite, "pw-c").boundingBox();
            assertEquals(d.y, a.y, 2.0, "D und A stehen nicht in derselben Zeile");
            assertTrue(a.x > d.x + d.width - 1, "A steht nicht rechts von D");
            assertTrue(c.y >= d.y + d.height, "C steht nicht unter D/A");
            assertTrue(c.width > d.width * 1.8, "C ist nicht ganz breit: " + c.width + " gegen " + d.width);

            // Schmale Ansicht (Handy): untereinander, jede ganz breit.
            seite.setViewportSize(390, 900);
            seite.waitForTimeout(300);
            BoundingBox dSchmal = zelle(seite, "pw-d").boundingBox();
            BoundingBox aSchmal = zelle(seite, "pw-a").boundingBox();
            assertTrue(aSchmal.y >= dSchmal.y + dSchmal.height, "auf schmaler Ansicht nicht untereinander");
            assertEquals(dSchmal.width, aSchmal.width, 2.0);
        }
    }

    @Test
    @Order(2)
    @DisplayName("Breite zurueck auf halb, danach Standard wiederherstellen")
    void halbeBreiteUndStandard() {
        try (BrowserContext kontext = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 1000))) {
            Page seite = startseite(kontext, ANNA);
            anpassen(seite);
            // Im Edit-Modus sind auch ausgeblendete Kacheln da (blass), damit man sie zurueckholen kann.
            assertEquals(List.of("pw-d", "pw-a", "pw-b", "pw-c"), reihenfolge(seite));
            assertTrue(zelle(seite, "pw-b").getAttribute("class").contains("dashboard-ausgeblendet"));

            zelle(seite, "pw-c").locator(".dashboard-schalter-halb").check();
            speichern(seite);
        }
        StartseitenLayout gespeichert = warteAufLayout(l -> l.getEintraege().stream()
                .anyMatch(e -> "pw-c".equals(e.getId()) && e.isHalbeBreite()));
        assertNotNull(gespeichert);

        try (BrowserContext kontext = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 1000))) {
            Page seite = startseite(kontext, ANNA);
            assertTrue(zelle(seite, "pw-c").getAttribute("class").contains("dashboard-halb"));

            anpassen(seite);
            seite.click("#fm\\:standard");
            seite.locator(".ui-confirmdialog-yes").first().click();
            seite.waitForFunction("() => document.querySelectorAll('#dashboard-grid .dashboard-zelle')[0]"
                    + " && document.querySelectorAll('#dashboard-grid .dashboard-zelle')[0].dataset.kachel === 'pw-a'");
            speichern(seite);
            assertEquals(List.of("pw-a", "pw-b", "pw-c", "pw-d"), reihenfolge(seite));
        }
    }

    /**
     * Rechte. Negativkontrolle: die ROOT-Kachel steht fuer Anna sogar sichtbar im gespeicherten
     * Layout (so, als haette ein manipuliertes Formular sie hineingeschrieben) — sie erscheint
     * trotzdem weder auf der Startseite noch im Edit-Modus. Positivkontrolle: ein ROOT-Benutzer
     * sieht dieselbe Kachel.
     */
    @Test
    @Order(3)
    @DisplayName("Rechte: ohne Modulrecht keine Kachel, auch nicht ueber das Layout; mit Recht schon")
    void rechte() {
        UserPreference prefs = userPrefs.findByUniqueId(ANNA);
        assertNotNull(prefs);
        prefs.getStartseiten().put(MANDAT, new StartseitenLayout(List.of(
                new StartseitenLayout.Eintrag("pw-root", true, false),
                new StartseitenLayout.Eintrag("pw-a", true, true))));
        userPrefs.save(prefs);

        try (BrowserContext kontext = browser.newContext()) {
            Page seite = startseite(kontext, ANNA);
            assertEquals(List.of("pw-a", "pw-b", "pw-c", "pw-d"), reihenfolge(seite));
            assertEquals(0, zelle(seite, "pw-root").count(), "ROOT-Kachel fuer ADMIN sichtbar");
            anpassen(seite);
            assertEquals(0, zelle(seite, "pw-root").count(), "ROOT-Kachel im Edit-Modus einblendbar");
            // Speichern schreibt die fremde Id nicht zurueck.
            speichern(seite);
        }
        StartseitenLayout nachher = warteAufLayout(l -> l.getEintraege().size() == 4);
        assertTrue(nachher.getEintraege().stream().noneMatch(e -> "pw-root".equals(e.getId())),
                "fremde Id ist im Layout geblieben: " + nachher.getEintraege());

        try (BrowserContext kontext = browser.newContext()) {
            Page seite = startseite(kontext, ROOT);
            assertEquals(1, zelle(seite, "pw-root").count(), "Positivkontrolle: ROOT sieht die ROOT-Kachel");
        }
    }

    // ── Hilfen ────────────────────────────────────────────────────────────

    private Page startseite(BrowserContext kontext, String benutzer) {
        Page seite = kontext.newPage();
        seite.navigate(url("/login.html"));
        seite.fill("#username", benutzer);
        seite.fill("#password", PASSWORT);
        seite.locator("#password").press("Enter");
        seite.waitForURL(u -> !u.contains("login"), new Page.WaitForURLOptions().setTimeout(90_000));
        seite.navigate(url("/index.html"));
        seite.waitForLoadState();
        seite.locator("#dashboard-grid").waitFor();
        return seite;
    }

    private static void anpassen(Page seite) {
        seite.click("#fm\\:anpassen");
        seite.locator(".dashboard-edit").first().waitFor();
    }

    private static void speichern(Page seite) {
        seite.click("#fm\\:speichern");
        seite.locator(".dashboard-edit").first().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.DETACHED));
        seite.locator("#fm\\:anpassen").waitFor();
    }

    private static Locator zelle(Page seite, String id) {
        return seite.locator("#dashboard-grid .dashboard-zelle[data-kachel='" + id + "']");
    }

    @SuppressWarnings("unchecked")
    private static List<String> reihenfolge(Page seite) {
        return (List<String>) seite.evaluate(
                "() => Array.from(document.querySelectorAll('#dashboard-grid .dashboard-zelle'))"
                        + ".map(z => z.dataset.kachel)");
    }

    /** Das Speichern laeuft per Ajax; bis zu 15 s warten, bis der Stand in der Datenbank steht. */
    private StartseitenLayout warteAufLayout(Predicate<StartseitenLayout> fertig) {
        long bis = System.currentTimeMillis() + 15_000;
        StartseitenLayout layout = null;
        while (System.currentTimeMillis() < bis) {
            UserPreference prefs = userPrefs.findByUniqueId(ANNA);
            layout = prefs == null ? null : prefs.getStartseiten().get(MANDAT);
            if (layout != null && fertig.test(layout)) {
                return layout;
            }
            // Gewartet wird auf die Datenbank, die Seite ist hier schon zu: parkNanos statt Thread.sleep
            // (Karte 1416, Sonar java:S2925), ohne InterruptedException.
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250));
        }
        assertNull(layout, "Layout erreicht den erwarteten Stand nicht: "
                + (layout == null ? null : layout.getEintraege()));
        throw new AssertionError("kein Layout fuer " + ANNA + " in der Ablage");
    }
}

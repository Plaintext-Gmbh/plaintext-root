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
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1548 (UI 2): die Grundlage im Browser — der Styleguide zeigt jeden Zustand, und jeder
 * Zustand wird hier am gerenderten DOM geprueft, hell und dunkel, gross und auf dem Telefon.
 *
 * <ul>
 *   <li><b>Seitenkopf</b>: ein h1, eine Hauptaktion, das «⋯»-Menue; die Hauptaktion meldet ueber
 *       das eine Growl des Templates.</li>
 *   <li><b>Tabelle</b>: Paginator nur bei mehr als einer Seite, deutscher Leerzustand mit Weg,
 *       nirgends «No records found», Zahlen mit {@code tabular-nums}.</li>
 *   <li><b>Dunkel</b>: der zweite Variablensatz greift (Statusfarbe anders als hell).</li>
 *   <li><b>Mobil 390</b>: Zeile als Karte, Spalten mit {@code pt-mobil-aus} weg, Filter erst nach
 *       dem Knopf.</li>
 * </ul>
 *
 * <p>Die Bildschirmfotos landen unter {@code target/ui-1548/} (Verzeichnis per
 * {@code -Dui.screenshots.dir} umlenkbar) — Belege fuer den PR, kein Vergleich gegen Referenzbilder.</p>
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
class UiGrundlagePlaywrightIT {

    private static final String BENUTZER = "pw-uigrundlage";
    private static final String PASSWORT = "Playwright-2026!";
    private static final String SEITE = "/styleguide.html";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "uigrundlageplaywrightit");
    }

    @LocalServerPort
    int port;
    @Autowired
    MyUserRepository userRepository;
    @Autowired
    PasswordEncoder passwordEncoder;

    private Playwright playwright;
    private Browser browser;
    private final List<BrowserContext> kontexte = new ArrayList<>();
    private final List<String> jsFehler = new ArrayList<>();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static Path bildVerzeichnis() {
        return Path.of(System.getProperty("ui.screenshots.dir", "target/ui-1548"));
    }

    @BeforeAll
    void setup() {
        if (userRepository.findByUsername(BENUTZER) == null) {
            MyUserEntity admin = new MyUserEntity();
            admin.setUsername(BENUTZER);
            admin.setMandat("default");
            admin.setPassword(passwordEncoder.encode(PASSWORT));
            admin.addRole("ROOT");
            admin.addRole("ADMIN");
            admin.addRole("USER");
            userRepository.save(admin);
        }
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "Chromium nicht installiert: " + e.getMessage());
        }
    }

    @AfterAll
    void teardown() {
        kontexte.forEach(BrowserContext::close);
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    /**
     * Eine angemeldete Seite in {@code modus} (light/dark) und der gegebenen Breite. Der Modus kommt
     * ueber das Cookie {@code plaintext-theme}, das {@code UserPreferencesBackingBean} beim Laden der
     * Einstellungen uebernimmt — derselbe Weg wie im Browser eines Benutzers.
     */
    private Page oeffne(String modus, int breite, int hoehe) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(breite, hoehe).setLocale("de-CH").setTimezoneId("Europe/Zurich"));
        kontexte.add(context);
        context.addCookies(List.of(new Cookie("plaintext-theme", modus).setUrl(url("/"))));
        Page page = context.newPage();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                jsFehler.add(modus + "/" + breite + ": " + msg.text());
            }
        });
        page.onPageError(fehler -> jsFehler.add(modus + "/" + breite + " pageerror: " + fehler));
        page.onRequestFailed(r -> jsFehler.add(modus + "/" + breite + " Anfrage gescheitert: " + r.url() + " " + r.failure()));

        page.navigate(url("/login.html"));
        page.waitForSelector("#username");
        page.fill("#username", BENUTZER);
        page.fill("#password", PASSWORT);
        page.locator("#password").press("Enter");
        page.waitForURL(u -> !u.contains("login"), new Page.WaitForURLOptions().setTimeout(90_000));

        // 90s und DOMContentLoaded wie die uebrigen ITs hier: der erste JSF-Aufruf kompiliert
        // Facelets, und auf den langsamen Runnern reicht die Voreinstellung nicht.
        page.navigate(url(SEITE), new Page.NavigateOptions().setTimeout(90_000)
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
        try {
            page.waitForSelector(".pt-seitenkopf h1", new Page.WaitForSelectorOptions().setTimeout(90_000));
        } catch (com.microsoft.playwright.TimeoutError e) {
            String html = page.content().replaceAll("\\s+", " ");
            int stelle = Math.max(0, html.indexOf("layout-content"));
            throw new AssertionError(SEITE + " zeigt keinen Seitenkopf (" + page.url() + "): "
                    + html.substring(stelle, Math.min(html.length(), stelle + 2500)), e);
        }
        assertEquals(modus, page.locator("html").getAttribute("data-theme"),
                "Der Modus kam nicht an — Cookie plaintext-theme wurde nicht uebernommen");
        return page;
    }

    private static Locator tabelle(Page page, String id) {
        return page.locator("[id='fm:" + id + "']");
    }

    private static String css(Page page, String selektor, String eigenschaft) {
        return (String) page.locator(selektor).first().evaluate(
                "(el, p) => getComputedStyle(el).getPropertyValue(p).trim()", eigenschaft);
    }

    private static String token(Page page, String name) {
        return (String) page.evaluate("n => getComputedStyle(document.documentElement).getPropertyValue(n).trim()", name);
    }

    private static void foto(Page page, String name) {
        page.screenshot(new Page.ScreenshotOptions().setFullPage(true)
                .setPath(bildVerzeichnis().resolve("styleguide-" + name + ".png")));
    }

    @Test
    @DisplayName("Desktop hell: Seitenkopf, Paginator ab Seite 2, Leerzustand mit Weg, Zahlen, Growl")
    void desktopHell() {
        Page page = oeffne("light", 1440, 900);

        // Prinzip 1: ein Titel, eine Hauptaktion, das «⋯»-Menue.
        assertEquals(1, page.locator(".pt-seitenkopf h1").count());
        assertEquals("Styleguide", page.locator(".pt-seitenkopf h1").innerText().trim());
        assertEquals(1, page.locator(".pt-seitenkopf .pt-hauptaktion button").count(),
                "Genau eine Hauptaktion im Seitenkopf");
        assertTrue(page.locator(".pt-seitenkopf .pt-mehr").isVisible(), "Das «⋯»-Menue fehlt");

        // Prinzip 2: Paginator nur, wo es mehr als eine Seite gibt.
        assertTrue(tabelle(page, "viele").locator(".ui-paginator").isVisible(),
                "27 Zeilen bei 10 je Seite — der Paginator muss da sein");
        assertFalse(tabelle(page, "wenige").locator(".ui-paginator").isVisible(),
                "3 Zeilen — ein Paginator unter einer einzigen Seite ist ein Bedienelement ohne Funktion");

        // Leerzustand: deutsch, mit Weg, und nirgends die englische Vorgabe.
        Locator leer = tabelle(page, "keine").locator(".pt-leer");
        assertTrue(leer.isVisible(), "Leerzustand fehlt");
        assertEquals("Noch keine Verträge.", leer.locator(".pt-leer-text").innerText().trim());
        assertEquals("Mit «Neu» legen Sie den ersten an.", leer.locator(".pt-leer-weg").innerText().trim());
        // Nur in den Tabellen gesucht: der Styleguide nennt die englische Vorgabe im Fliesstext.
        List<String> tabellenTexte = page.locator(".ui-datatable").allInnerTexts();
        assertTrue(tabellenTexte.stream().noneMatch(t -> t.contains("No records found")),
                "Englischer Leerzustand in einer Tabelle: " + tabellenTexte);

        // Zahlen: rechtsbuendig, gleich breite Ziffern.
        assertTrue(css(page, "[id='fm:viele'] td.pt-zahl", "font-variant-numeric").contains("tabular-nums"),
                "pt:colAmount ohne tabular-nums");
        assertEquals("right", css(page, "[id='fm:viele'] td.pt-zahl", "text-align"));

        // Auf dem grossen Bildschirm stehen die Filter offen, der Knopf fehlt.
        assertTrue(page.locator(".pt-filter-inhalt").isVisible());
        assertFalse(page.locator(".pt-filter-knopf").isVisible());

        foto(page, "hell-1440");

        // Prinzip 3: die Hauptaktion meldet ueber das eine Growl des Templates.
        page.locator(".pt-seitenkopf .pt-hauptaktion button").click();
        Locator growl = page.locator("[id='globalMessagesForm:globalGrowl_container'] .ui-growl-message");
        growl.first().waitFor();
        assertTrue(growl.first().innerText().contains("FacesMessages"), "Meldung kam nicht im globalen Growl an");
        assertEquals(1, page.locator(".ui-growl").count(), "Es gibt genau ein Growl auf der Seite");

        assertTrue(jsFehler.isEmpty(), "JS-Fehler: " + jsFehler);
    }

    @Test
    @DisplayName("Desktop dunkel: der zweite Variablensatz greift")
    void desktopDunkel() {
        Page hell = oeffne("light", 1440, 900);
        String okHell = token(hell, "--pt-status-ok");
        String flaecheHell = css(hell, ".sg-abschnitt", "background-color");

        Page dunkel = oeffne("dark", 1440, 900);
        String okDunkel = token(dunkel, "--pt-status-ok");
        String flaecheDunkel = css(dunkel, ".sg-abschnitt", "background-color");

        assertNotEquals(okHell, okDunkel, "Statusfarbe hell und dunkel gleich — dunkler Variablensatz fehlt");
        assertNotEquals(flaecheHell, flaecheDunkel, "--pt-flaeche folgt dem Dunkelmodus nicht");
        assertTrue(tabelle(dunkel, "keine").locator(".pt-leer").isVisible());

        foto(dunkel, "dunkel-1440");
        assertTrue(jsFehler.isEmpty(), "JS-Fehler: " + jsFehler);
    }

    @Test
    @DisplayName("Mobil 390: Tabelle als Karten mit wenigen Feldern, Filter hinter Knopf (hell und dunkel)")
    void mobil() {
        for (String modus : List.of("light", "dark")) {
            Page page = oeffne(modus, 390, 844);

            // Zeile als Karte: die Zeile hat einen Rahmen und Radius, die Spalten ohne Not sind weg.
            Locator ersteZeile = tabelle(page, "viele").locator(".ui-datatable-data > tr").first();
            assertEquals("flow-root", (String) ersteZeile.evaluate("el => getComputedStyle(el).display"));
            // Die Karte umschliesst ihre Felder: sie ist hoeher als ein blosser Rahmen.
            Number hoehe = (Number) ersteZeile.evaluate("el => el.getBoundingClientRect().height");
            assertTrue(hoehe.intValue() > 60, "Karte ist nur " + hoehe + "px hoch — die Felder liegen ausserhalb");
            assertNotEquals("0px", (String) ersteZeile.evaluate("el => getComputedStyle(el).borderTopLeftRadius"));
            Locator sichtbareZellen = ersteZeile.locator("td:visible");
            int sichtbar = sichtbareZellen.count();
            assertTrue(sichtbar >= 2 && sichtbar <= 4,
                    "Karte zeigt " + sichtbar + " Felder — erwartet 2-3 plus Zeilenaktionen");
            assertFalse(ersteZeile.locator("td.pt-mobil-aus").first().isVisible(), "pt-mobil-aus ist mobil sichtbar");

            // Filter: zu, bis der Knopf gedrueckt ist.
            assertTrue(page.locator(".pt-filter-knopf").isVisible(), "Filterknopf fehlt mobil");
            assertFalse(page.locator(".pt-filter-inhalt").isVisible(), "Filter mobil schon offen");
            page.locator(".pt-filter-knopf").click();
            assertTrue(page.locator(".pt-filter-inhalt").isVisible(), "Filterknopf oeffnet nichts");
            assertEquals("true", page.locator(".pt-filter-knopf").getAttribute("aria-expanded"));
            page.locator(".pt-filter-knopf").click();
            assertFalse(page.locator(".pt-filter-inhalt").isVisible(), "Filterknopf schliesst nicht");

            // Kein waagrechtes Scrollen auf dem Telefon.
            Number ueberstand = (Number) page.evaluate(
                    "() => document.documentElement.scrollWidth - document.documentElement.clientWidth");
            assertTrue(ueberstand.intValue() <= 0, "Die Seite ist mobil " + ueberstand + "px zu breit");

            foto(page, ("light".equals(modus) ? "hell" : "dunkel") + "-390");
        }
        assertTrue(jsFehler.isEmpty(), "JS-Fehler: " + jsFehler);
    }
}

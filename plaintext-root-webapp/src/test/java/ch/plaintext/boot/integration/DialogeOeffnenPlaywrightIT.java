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
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1057 (uebertragen aus Karte 1012, plaintext-app): der Durchgang, der die Seiten nicht
 * nur laedt, sondern <b>bedient</b>.
 *
 * <p><b>Warum es diesen Test gibt.</b> Alle vier Fehler, die Daniel Ende August 2026 selbst
 * gemeldet hat (Karten 1013–1016), traten erst beim Bedienen auf: ein Dialog, der leer aufgeht;
 * ein Speichern, das nichts speichert; eine zweite Zeile, die nicht übernommen wird. Ein Durchgang,
 * der nur Statuscodes prüft, bleibt dabei grün. {@link AllPagesSmokePlaywrightIT} lädt jede Seite —
 * dieser Test klickt danach auf jeder Seite die Knöpfe, die einen Dialog öffnen, und sieht nach,
 * was dabei herauskommt.
 *
 * <p><b>Das Fehlerbild, das er sucht</b> (Karte 1016): ein Ajax-Suchausdruck läuft über eine ID,
 * die kein NamingContainer ist — {@code update=":fm:dlg:feld"}. Beim Rendern der Teilantwort wirft
 * JSF {@code IllegalArgumentException: <id>}. PrimeFaces bekommt statt des Dialoginhalts eine
 * Fehlerantwort, das Overlay geht leer auf und die Seite ist blockiert. <b>Der HTTP-Status dieser
 * Antwort ist 200.</b> Sichtbar ist der Fehler nur an drei Stellen, und alle drei wertet dieser
 * Test aus:
 *
 * <ol>
 *   <li>{@code <error-name>} im XML der Teilantwort,</li>
 *   <li>eine Ausnahme, die im DOM landet,</li>
 *   <li>ein leerer Dialog: sichtbar, aber ohne ein einziges Eingabefeld.</li>
 * </ol>
 *
 * <p><b>Die Kontrolle, ohne die das Ergebnis wertlos wäre</b>, steht in
 * {@link #derDurchgangHatWirklichBedient()}: findet der Durchgang keine Knöpfe und öffnet keine
 * Dialoge, ist er trivial grün. Genau daran ist der erste Anlauf zu Karte 1012 gescheitert.
 *
 * <p><b>Was er tatsächlich anfasst, gemessen statt behauptet</b> (app/guild, Lauf vom 01.09.2026):
 * 86 Seiten mit Dialog, <b>43 gedrueckte Knoepfe</b>, 3 aufgegangene Dialoge. Fuer root stehen
 * die Zahlen dieses Repos im Bauprotokoll. Die Zahlen stehen bei
 * jedem Lauf im Bauprotokoll — wer sie nicht sieht, hat keinen Bedien-Durchgang gehabt.
 *
 * <p><b>Warum nur 3 Dialoge bei 43 Klicks, und warum das kein Fehler ist:</b> die Testdatenbank
 * ist frisch, also sind fast alle Listen leer. „Bearbeiten" gibt es nur je Zeile, und Zeilen gibt
 * es nicht; übrig bleiben „Neu"-Knöpfe, von denen viele auf eine eigene Seite führen statt in
 * einen Dialog. Der Ertrag des Durchgangs liegt deshalb weniger im Zählen der Overlays als in den
 * 43 <i>tatsächlich ausgelösten</i> Ajax-Vorgängen, deren Antwortrumpf jeweils auf
 * {@code <error-name>} gelesen wird. Wer mehr Dialoge sehen will, muss die Testdatenbank füllen —
 * das ist der nächste Ausbauschritt und steht in der Folgekarte zu 1012.
 *
 * <p><b>Was der Test NICHT leistet:</b> Er füllt keine Formulare aus und speichert nicht. Ein
 * Speichern, das ohne Fehlermeldung nichts schreibt (readOnly-Transaktion, falsche Propagation),
 * bleibt unentdeckt — dafür braucht es fachliche Tests je Maske.
 *
 * @author info@plaintext.ch
 * @since 2026
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DialogeOeffnenPlaywrightIT {

    private static final Logger LOG = LoggerFactory.getLogger(DialogeOeffnenPlaywrightIT.class);

    private static final String BENUTZER = "pw-dialoge";
    private static final String PASSWORT = "Playwright-2026!";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "rootdialoge");
    }

    /** Beschriftungen, hinter denen ueblicherweise ein Dialog aufgeht. */
    private static final Pattern OEFFNET_DIALOG = Pattern.compile(
            "(?i)\\b(neu|neue|neuer|neues|hinzuf|erfassen|anlegen|bearbeiten|einladen|"
                    + "importieren|filter)\\w*");

    /** Fehlerantwort einer PrimeFaces-Teilantwort. */
    private static final Pattern AJAX_FEHLER = Pattern.compile(
            "<error-name>|<error-message>|jakarta\\.faces\\.[A-Za-z.]*Exception"
                    + "|java\\.lang\\.[A-Za-z]+Exception");

    /** Ausnahme, die es bis ins gerenderte DOM geschafft hat. */
    private static final Pattern AUSNAHME_IM_DOM = Pattern.compile(
            "Whitelabel Error Page|Internal Server Error"
                    + "|jakarta\\.faces\\.[A-Za-z.]*Exception"
                    + "|java\\.lang\\.[A-Za-z]+(Exception|Error)");

    @LocalServerPort
    int port;
    @Autowired
    MyUserRepository userRepository;
    @Autowired
    PasswordEncoder passwordEncoder;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    private final List<String> jsFehler = new ArrayList<>();
    private final List<String> ajaxFehler = new ArrayList<>();

    /** Kontrollzaehler — siehe {@link #derDurchgangHatWirklichBedient()}. */
    private static final AtomicInteger SEITEN_MIT_DIALOG = new AtomicInteger();
    private static final AtomicInteger GEKLICKTE_KNOEPFE = new AtomicInteger();
    private static final AtomicInteger GEOEFFNETE_DIALOGE = new AtomicInteger();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    static Stream<String> alleSeiten() {
        return Seitenliste.alle().stream();
    }

    @BeforeAll
    void setup() {
        // Rollen OHNE "ROLE_"-Praefix: MyUserDetailsService setzt es selbst davor.
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

        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1920, 1080).setLocale("de-CH").setTimezoneId("Europe/Zurich"));
        page = context.newPage();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                jsFehler.add(msg.text());
            }
        });
        // Der eigentliche Detektor: PrimeFaces liefert die Fehlermeldung IN der Teilantwort aus,
        // mit HTTP 200. Ohne diesen Blick in den Rumpf bleibt Karte 1016 unsichtbar.
        page.onResponse(antwort -> {
            if (!"POST".equals(antwort.request().method())) {
                return;
            }
            try {
                String rumpf = antwort.text();
                if (rumpf.startsWith("<?xml") && AJAX_FEHLER.matcher(rumpf).find()) {
                    ajaxFehler.add(kurz(rumpf));
                }
            } catch (RuntimeException e) {
                // Rumpf nicht mehr abrufbar (Weiterleitung, Abbruch) — kein Befund.
            }
        });

        page.navigate(url("/login.html"));
        page.waitForSelector("#username");
        page.fill("#username", BENUTZER);
        page.fill("#password", PASSWORT);
        page.locator("#password").press("Enter");
        page.waitForURL(u -> !u.contains("login"), new Page.WaitForURLOptions().setTimeout(90_000));
    }

    @AfterAll
    void teardown() {
        if (context != null) context.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("alleSeiten")
    void dialogeGehenAufUndSindNichtLeer(String pfad) {
        if (pfad.startsWith("/login") || pfad.startsWith("/logout") || pfad.contains("access-denied")) {
            return; // anonyme Rahmenseiten, hier nicht zu bedienen
        }
        jsFehler.clear();
        ajaxFehler.clear();
        try {
            page.navigate(url(pfad), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60_000));
        } catch (RuntimeException e) {
            return; // Ladefehler sind Sache von AllPagesSmokePlaywrightIT, nicht dieses Tests
        }
        warten();

        if (page.locator(".ui-dialog").count() == 0) {
            return; // Seite ohne Dialog — nichts zu bedienen
        }
        SEITEN_MIT_DIALOG.incrementAndGet();

        List<String> funde = new ArrayList<>();
        // ZWEI Einschraenkungen, beide gemessen und nicht geraten:
        //
        // 1. NUR im Inhaltsbereich. Die Rahmenseite setzt Menue und Kopfzeile VOR den Inhalt; eine
        //    Suche ueber die ganze Seite verbraucht ihr Budget an Menuepunkten und erreicht die
        //    Knoepfe der Maske nie.
        // 2. Vorrangig Knoepfe, deren `onclick` ein `.show()` enthaelt. PrimeFaces schreibt die
        //    Ajax-Konfiguration mitsamt `oncomplete` in das onclick-Attribut — ein Knopf, der
        //    einen Dialog oeffnet, traegt dort `PF('…').show()`. Das ist die Eigenschaft, auf die
        //    es ankommt, statt einer Beschriftung zu glauben. Die Beschriftungssuche bleibt als
        //    zweiter Weg fuer Knoepfe, die den Dialog aus einem eigenen Skript oeffnen.
        Locator knoepfe = page.locator(
                ".layout-content button[onclick*='.show()']:visible,"
                        + " .layout-content a[onclick*='.show()']:visible,"
                        + " .layout-content button:visible,"
                        + " .layout-content a.ui-commandlink:visible");
        int anzahl = Math.min(knoepfe.count(), 25);
        for (int i = 0; i < anzahl; i++) {
            Locator knopf = knoepfe.nth(i);
            String text = beschriftung(knopf);
            if (!oeffnetVermutlichEinenDialog(knopf, text)) {
                continue;
            }
            jsFehler.clear();
            ajaxFehler.clear();
            try {
                knopf.click(new Locator.ClickOptions().setTimeout(5_000));
            } catch (RuntimeException e) {
                continue; // Knopf nicht anklickbar (verdeckt, deaktiviert) — kein Befund
            }
            GEKLICKTE_KNOEPFE.incrementAndGet();
            warten();

            if (!ajaxFehler.isEmpty()) {
                funde.add("Knopf '" + text + "': Fehler in der Ajax-Teilantwort -> " + ajaxFehler);
            }
            if (!jsFehler.isEmpty()) {
                funde.add("Knopf '" + text + "': JS-Konsolenfehler -> " + jsFehler);
            }
            String dom = page.content();
            if (AUSNAHME_IM_DOM.matcher(dom).find()) {
                funde.add("Knopf '" + text + "': Ausnahme im DOM");
            }

            Locator offen = page.locator(".ui-dialog:visible");
            if (offen.count() > 0) {
                GEOEFFNETE_DIALOGE.incrementAndGet();
                Locator felder = offen.first().locator("input, select, textarea, .ui-datatable, button");
                if (felder.count() == 0) {
                    funde.add("Knopf '" + text + "': Dialog geht LEER auf (kein einziges Element) "
                            + "— genau das Bild aus Karte 1016");
                }
                schliessen();
            }
        }
        assertTrue(funde.isEmpty(), pfad + ":\n  " + String.join("\n  ", funde));
    }

    /**
     * Die Kontrolle, ohne die der Lauf oben nichts belegt. Klickt der Durchgang nichts an und geht
     * kein Dialog auf, ist jedes „alles grün" trivial — es wurde nichts geprüft. Die Schwellen sind
     * bewusst niedrig: sie sollen einen <i>abgerissenen</i> Durchgang erkennen, nicht eine
     * bestimmte Anwendungsgrösse festschreiben.
     */
    @Order(2)
    @Test
    @DisplayName("Kontrolle: der Durchgang hat wirklich Knoepfe gedrueckt und Dialoge geoeffnet")
    void derDurchgangHatWirklichBedient() {
        // Die Zahlen gehoeren ins Bauprotokoll, nicht nur in eine Fehlermeldung: nur so ist
        // hinterher belegbar, WIE VIEL der Durchgang bedient hat.
        LOG.info("Bedien-Durchgang: {} Seiten mit Dialog, {} Knoepfe gedrueckt, {} Dialoge offen",
                SEITEN_MIT_DIALOG.get(), GEKLICKTE_KNOEPFE.get(), GEOEFFNETE_DIALOGE.get());
        assertTrue(SEITEN_MIT_DIALOG.get() >= 5,
                "Nur " + SEITEN_MIT_DIALOG.get() + " Seiten mit Dialog gefunden — der Durchgang "
                        + "hat praktisch nichts geprueft.");
        assertTrue(GEKLICKTE_KNOEPFE.get() >= 10,
                "Nur " + GEKLICKTE_KNOEPFE.get() + " Knoepfe gedrueckt — der Durchgang laeuft "
                        + "leer und sein gruenes Ergebnis waere wertlos.");
        // Zwei statt drei wie in app/guild — gemessen, nicht gesenkt, bis es passt: der erste
        // Lauf in root ergab 32 Seiten mit Dialog, 17 gedrueckte Knoepfe und 2 offene Dialoge.
        // root ist das Framework: seine Masken sind Verwaltungslisten, und in einer frischen
        // Testdatenbank gibt es keine Zeilen — "Bearbeiten" existiert nur je Zeile. Uebrig
        // bleiben "Neu"-Knoepfe, von denen die meisten auf eine eigene Seite fuehren statt in
        // einen Dialog. Die Schwelle soll einen ABGERISSENEN Durchgang erkennen, nicht eine
        // Anwendungsgroesse festschreiben.
        assertTrue(GEOEFFNETE_DIALOGE.get() >= 2,
                "Nur " + GEOEFFNETE_DIALOGE.get() + " Dialoge sind aufgegangen — dann prueft "
                        + "'Dialog geht leer auf' nichts.");
    }

    /**
     * Positivkontrolle des Detektors am wörtlichen Fehlerbild aus Karte 1016: eine
     * PrimeFaces-Teilantwort mit {@code IllegalArgumentException}. Schlägt sie nicht an, ist der
     * Durchgang oben blind für genau den Fehler, den er finden soll.
     */
    @Order(3)
    @Test
    @DisplayName("Positivkontrolle: die Fehlerantwort aus Karte 1016 wird erkannt")
    void positivkontrolleAjaxFehlerantwort() {
        String antwort = """
                <?xml version='1.0' encoding='UTF-8'?>
                <partial-response><error>\
                <error-name>class java.lang.IllegalArgumentException</error-name>\
                <error-message><![CDATA[dlg]]></error-message>\
                </error></partial-response>
                """;
        assertTrue(AJAX_FEHLER.matcher(antwort).find(),
                "Die Fehlerantwort aus Karte 1016 faellt nicht auf — der Durchgang ist blind.");
        assertTrue(!AJAX_FEHLER.matcher("""
                <?xml version='1.0' encoding='UTF-8'?>
                <partial-response><changes><update id="fm"><![CDATA[<div>ok</div>]]></update>\
                </changes></partial-response>
                """).find(), "Negativkontrolle: eine gesunde Teilantwort ist kein Befund.");
    }

    // ------------------------------------------------------------------------------------------

    private void warten() {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(4_000));
        } catch (RuntimeException e) {
            page.waitForTimeout(500);
        }
    }

    private void schliessen() {
        try {
            page.keyboard().press("Escape");
            page.waitForTimeout(300);
            Locator x = page.locator(".ui-dialog:visible .ui-dialog-titlebar-close");
            if (x.count() > 0) {
                x.first().click(new Locator.ClickOptions().setTimeout(2_000));
            }
        } catch (RuntimeException e) {
            // Dialog laesst sich nicht schliessen — die naechste Navigation raeumt ihn weg.
        }
    }

    /**
     * Ein Knopf kommt in Frage, wenn sein {@code onclick} ein {@code .show()} enthaelt (so
     * schreibt PrimeFaces das {@code oncomplete} eines Dialog-Oeffners hin) oder wenn seine
     * Beschriftung nach „Neu / Bearbeiten / Erfassen …" aussieht.
     */
    private static boolean oeffnetVermutlichEinenDialog(Locator knopf, String beschriftung) {
        try {
            String onclick = knopf.getAttribute("onclick");
            if (onclick != null && onclick.contains(".show()")) {
                return true;
            }
        } catch (RuntimeException e) {
            // Element inzwischen weg — dann eben ueber die Beschriftung entscheiden.
        }
        return !beschriftung.isBlank() && OEFFNET_DIALOG.matcher(beschriftung).find();
    }

    private static String beschriftung(Locator knopf) {
        try {
            String text = knopf.innerText(new Locator.InnerTextOptions().setTimeout(2_000));
            if (text != null && !text.isBlank()) {
                return text.trim().toLowerCase(Locale.ROOT);
            }
            String titel = knopf.getAttribute("title");
            return titel == null ? "" : titel.trim().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String kurz(String s) {
        String eine = s.replaceAll("\\s+", " ");
        return eine.substring(0, Math.min(400, eine.length()));
    }
}

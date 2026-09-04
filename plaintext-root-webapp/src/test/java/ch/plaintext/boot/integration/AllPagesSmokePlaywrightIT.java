/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1029 (uebertragen aus Karte 1012): <b>jede</b> Seite des laufenden root wird als
 * angemeldeter ROOT geladen und auf Serverfehler geprueft.
 *
 * <p><b>Was es vorher gab, und warum es nicht reichte.</b> root hat Playwright-Tests
 * ({@link RootPagesPlaywrightIT}, {@link SelfServicePlaywrightIT}) — die pruefen einzelne,
 * benannte Masken. Was fehlte, ist der <b>vollstaendige</b> Durchgang: jede Seite, ohne dass
 * jemand sie in eine Liste eintraegt. Die Folge stand in plaintext-app am 01.09.2026 in PROD:
 * {@code /auszahlungeinstellungen.xhtml} antwortete seit 26 Releases mit HTTP 500, weil ein
 * Kommentar {@code --} enthielt. Keine Suite lud die Seite, also fiel es erst auf, als Daniel sie
 * oeffnete.
 *
 * <p><b>Warum das fuer root besonders zaehlt:</b> die 53 Views dieses Repos landen in
 * <i>jeder</i> der fuenf Anwendungen. Eine kaputte Seite hier ist fuenfmal kaputt.
 *
 * <p><b>Warum ein Statuscode allein nichts belegt.</b> Eine Seite, die auf {@code /login}
 * umleitet, antwortet mit 200. Deshalb prueft jeder Fall zusaetzlich, dass er nicht umgeleitet
 * wurde, dass im HTML keine Ausnahme steht, dass die JS-Konsole leer bleibt und dass keine
 * Ressource mit 4xx/5xx nachgeladen wird.
 *
 * <p><b>Was der Test NICHT leistet:</b> Er bedient nichts. Ein Dialog, der leer aufgeht, oder ein
 * Speichern, das nichts speichert, faellt hier nicht auf.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.docker.compose.enabled=false",
                // Ohne diese Schalter laufen die Crons im Testkontext weiter und schreiben
                // Hikari-Timeouts in die Ausgabe der jeweils laufenden Klasse.
                "plaintext.cron.default-enabled=false",
                "plaintext.cron.default-startup=false"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllPagesSmokePlaywrightIT {

    private static final String BENUTZER = "pw-alleseiten";
    private static final String PASSWORT = "Playwright-2026!";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "allpagessmokeplaywrightit");
    }

    /**
     * Ausnahme im HTML — der sichere Hinweis auf einen Serverfehler, auch wenn HTTP 200 kommt.
     * Bewusst eng gefasst: nur voll qualifizierte Ausnahme-Klassennamen und die beiden
     * Fehlerseiten-Ueberschriften, damit fachlicher Seitentext keinen Fehlalarm ausloest.
     */
    private static final Pattern AUSNAHME_IM_HTML = Pattern.compile(
            "Whitelabel Error Page|Internal Server Error"
                    + "|jakarta\\.servlet\\.[A-Za-z.]*Exception"
                    + "|jakarta\\.faces\\.[A-Za-z.]*Exception"
                    + "|java\\.lang\\.[A-Za-z]+(Exception|Error)"
                    + "|org\\.xml\\.sax\\.SAXParseException"
                    + "|Error Rendering View");

    /**
     * Die anonymen Rahmenseiten. Dass sie das Anmeldeformular zeigen, ist ihr Zweck und kein
     * Befund — sie werden trotzdem geladen und auf Serverfehler geprueft.
     */
    private static final List<String> ANONYM = List.of("/login.xhtml", "/logout.xhtml",
            // Die TOTP-Maske ist nur waehrend einer angefangenen Anmeldung erreichbar; wer schon
            // angemeldet ist, wird zurueck aufs Login geschickt. Gemessen am 04.09.2026 — das ist
            // ihr Verhalten, kein Befund. Geladen und auf Serverfehler geprueft wird sie trotzdem.
            "/login-totp.xhtml");

    /**
     * Bekannte, <b>gemeldete</b> JS-Konsolenfehler. Jeder Eintrag ist ein offener Befund mit
     * Karte, kein Freibrief: die Seite wird weiterhin geladen und auf Serverfehler geprueft, nur
     * die Konsolenmeldung ist hier vorgemerkt.
     *
     * <p>{@code /cron.xhtml} meldet in plaintext-app zweimal
     * {@code Widget for var 'blockIt' not available!} (gefunden vom app-Durchgang am 01.09.2026,
     * Karte 1029). Die Seite liegt in {@code plaintext-admin-cron}, also hier. Der Eintrag steht
     * vorsorglich: der Fehler haengt an den gerenderten Tabellenzeilen, und die Testdatenbank
     * dieses Laufs hat keine Cron-Jobs — er wird hier also voraussichtlich gar nicht auftreten.
     * Ohne den Eintrag waere der Durchgang bei der ersten gefuellten Datenbank rot.
     */
    private static final Map<String, String> BEKANNTE_JS_FEHLER = Map.of(
            "/cron.xhtml", "Widget for var 'blockIt' not available!");

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
    private final List<String> fehlendeRessourcen = new ArrayList<>();

    private String url(String path) {
        return "http://localhost:" + port + path;
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

        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "Chromium nicht installiert: " + e.getMessage());
        }
        context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1920, 1080).setLocale("de-CH").setTimezoneId("Europe/Zurich"));
        page = context.newPage();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                jsFehler.add(msg.text());
            }
        });
        // Eine CSS- oder JS-Datei, die 404 liefert, macht keinen Laut: die Seite kommt mit
        // HTTP 200, sieht aber falsch aus oder reagiert nicht. Nur hier faellt das auf.
        page.onResponse(antwort -> {
            if (antwort.status() >= 400 && !antwort.url().contains("/login")) {
                fehlendeRessourcen.add(antwort.status() + " " + antwort.url());
            }
        });

        page.navigate(url("/login.html"));
        page.waitForSelector("#username");
        page.fill("#username", BENUTZER);
        page.fill("#password", PASSWORT);
        page.locator("#password").press("Enter");
        try {
            // 90s statt 30s: die self-hosted Runner booten und rendern deutlich langsamer als
            // die lokale Maschine (der erste JSF-Request kompiliert Facelets).
            page.waitForURL(u -> !u.contains("login"), new Page.WaitForURLOptions().setTimeout(90_000));
        } catch (com.microsoft.playwright.TimeoutError e) {
            String html = page.content();
            throw new AssertionError("Login blieb haengen auf " + page.url() + " — Seitenauszug: "
                    + html.substring(0, Math.min(1500, html.length())), e);
        }
    }

    @AfterAll
    void teardown() {
        if (context != null) context.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    static Stream<String> alleSeiten() {
        return Seitenliste.alle().stream();
    }

    /**
     * Kontrolle, ohne die ein gruener Lauf nichts belegt: findet die Ableitung ueberhaupt Seiten,
     * und findet sie Seiten aus <b>anderen</b> Modulen? Greift der Klassenpfad-Scan ins Leere,
     * liefe der Durchgang unten ueber null Faelle — und waere trotzdem gruen. Genau dieser Fehler
     * hat den ersten Anlauf zu Karte 1012 wertlos gemacht.
     */
    @Test
    @DisplayName("die Seitenliste stammt aus dem Klassenpfad und deckt mehrere Module ab")
    void dieSeitenlisteGreift() {
        List<String> seiten = Seitenliste.alle();
        // 35 Seiten am 04.09.2026: die 53 XHTML-Dateien des Repos minus Bausteine
        // (includes/, template*). Die Grenze liegt darunter, aber weit genug oben, dass ein
        // leerer oder halber Klassenpfad-Scan auffaellt.
        assertTrue(seiten.size() > 30,
                "Nur " + seiten.size() + " Views auf dem Klassenpfad gefunden — die Ableitung "
                        + "greift ins Leere, das Ergebnis des Durchgangs waere wertlos: " + seiten);
        assertTrue(seiten.contains("/login.xhtml"), "Login-Seite fehlt in " + seiten);
        assertTrue(seiten.contains("/cron.xhtml"),
                "Die Seite aus plaintext-admin-cron fehlt — dann erfasst der Scan nur das eigene "
                        + "Modul und die Luecke aus Karte 1012 bleibt offen: " + seiten);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("alleSeiten")
    void seiteLaedtOhneServerUndJsFehler(String pfad) {
        jsFehler.clear();
        fehlendeRessourcen.clear();
        // 60s statt der Voreinstellung 30s: die Seite gilt erst als geladen, wenn auch jede
        // Ressource durch ist. Laeuft sie in den Timeout, ist das ein Befund und keine Panne —
        // die Meldung nennt deshalb die Seite und nicht nur "Timeout".
        Response response;
        try {
            response = page.navigate(url(pfad), new Page.NavigateOptions().setTimeout(60_000));
        } catch (com.microsoft.playwright.TimeoutError e) {
            throw new AssertionError(pfad + " war nach 60s nicht fertig geladen. Offene "
                    + "Ressourcen mit Fehlerstatus: " + fehlendeRessourcen, e);
        }
        page.waitForLoadState();
        String html = page.content();

        assertTrue(response == null || response.status() < 500,
                pfad + " antwortete mit HTTP " + (response != null ? response.status() : "?"));

        Matcher ausnahme = AUSNAHME_IM_HTML.matcher(html);
        assertTrue(!ausnahme.find(),
                pfad + " zeigt einen Serverfehler: " + auszug(html, ausnahme.reset().find()
                        ? ausnahme.start() : 0));

        if (!ANONYM.contains(pfad)) {
            assertTrue(!page.url().contains("login"),
                    pfad + " leitete auf das Login um — Session/Rollen der Test-Anmeldung pruefen");
        }
        String bekannt = BEKANNTE_JS_FEHLER.get(pfad);
        List<String> neueJsFehler = jsFehler.stream()
                .filter(f -> bekannt == null || !f.contains(bekannt))
                .toList();
        assertTrue(neueJsFehler.isEmpty(), pfad + " JS-Konsolenfehler: " + neueJsFehler);
        assertTrue(fehlendeRessourcen.isEmpty(),
                pfad + " laedt Ressourcen, die es nicht gibt: " + fehlendeRessourcen
                        + " — eine CSS- oder JS-Datei, die 404 liefert, bleibt im Browser stumm, "
                        + "die Seite sieht nur falsch aus oder reagiert nicht.");
    }

    private static String auszug(String html, int stelle) {
        int von = Math.max(0, stelle - 200);
        return html.substring(von, Math.min(html.length(), von + 900)).replaceAll("\\s+", " ");
    }
}

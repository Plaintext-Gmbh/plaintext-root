/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISettingsService;
import ch.plaintext.settings.SettingsKeys;
import ch.plaintext.testsupport.EmbeddedPg;
import ch.plaintext.watch.mobil.MobilAntwort;
import ch.plaintext.watch.mobil.MobilSeite;
import ch.plaintext.watch.mobil.MobilWatchPage;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1355: das Mobil-Framework im Browser — eine Seite, die nur aus einer Beschreibung
 * besteht, wird gezeichnet, bedient und bewacht.
 *
 * <h2>Was diese Klasse belegt, und warum jede Aussage eine Gegenprobe hat</h2>
 *
 * <ul>
 *   <li><b>Die Seite laedt sauber</b>: HTTP 200, hoechstens drei Anfragen, kein Konsolenfehler,
 *       keine CSP-Verletzung, und der CSP-Header ist der strenge des Hauses (kein
 *       {@code unsafe-eval}). Gegenprobe: der Konsolen-Sammler meldet einen Fehler, wenn man ihm
 *       einen unterschiebt — sonst belegt „null Fehler" nur, dass er nicht zuhoert.</li>
 *   <li><b>Eine Aktion laedt die Seite nicht neu</b>: eine Marke im {@code window} ueberlebt
 *       den Druck, der Zaehler steigt <em>im Dienst</em> (nicht nur in der Anzeige).</li>
 *   <li><b>Ohne JavaScript geht es auch</b>: derselbe Druck als gewoehnliches Formular, 303
 *       und neue Seite.</li>
 *   <li><b>Sicherheit</b>: ohne Anmeldung die Anmeldeseite; ein POST ohne CSRF-Token 403 und
 *       keine Wirkung (Positivkontrolle: mit Token wirkt er); eine gesperrte Seite 404; mit
 *       Handy-Link erreichbar und bedienbar, nach dem Abschalten des Links nicht mehr.</li>
 * </ul>
 *
 * <p>Die Seite selbst ist eine Testseite ({@link ZaehlSeite}): root hat keine Fachseite, und
 * das Framework soll hier ohne ein Fachmodul bewiesen werden. Die echte Seite (Alkohol) belegt
 * app mit {@code MobilAlkoholPlaywrightIT}.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.docker.compose.enabled=false",
                // Wie WatchHandyLinkPlaywrightIT: der strenge Eimer von /nosec/watch.
                "plaintext.rate-limit.nosec-token.max-requests=500"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(MobilSeitePlaywrightIT.TestSeiten.class)
class MobilSeitePlaywrightIT {

    private static final String BENUTZER = "pw-mobil";
    /** Ein Benutzer ohne die Rollen der Watch-Einstellungen (USER/ADMIN/ROOT), Karte 1387. */
    private static final String GAST = "pw-mobil-gast";
    private static final String PASSWORT = "Playwright-2026!";

    private static final String SEITE = "/watch/m/zaehler";
    private static final String AKTION = "/watch/m/zaehler/plus";

    /** Zaehlt je Benutzer, wie oft gedrueckt wurde — der Beleg liegt im Dienst, nicht im DOM. */
    static final Map<String, AtomicInteger> STAND = new ConcurrentHashMap<>();

    /** Die Testseite: ein Zaehler mit einem Knopf. */
    static class ZaehlSeite implements MobilWatchPage {
        private final String id;
        private final boolean erlaubt;

        ZaehlSeite(String id, boolean erlaubt) {
            this.id = id;
            this.erlaubt = erlaubt;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return "Zähler";
        }

        @Override
        public int order() {
            return 5;
        }

        @Override
        public boolean available() {
            return erlaubt;
        }

        private static AtomicInteger stand() {
            return STAND.computeIfAbsent(String.valueOf(PlaintextSecurityHolder.getUser()), k -> new AtomicInteger());
        }

        @Override
        public MobilSeite beschreibe() {
            return MobilSeite.neu()
                    .wert("Stand", String.valueOf(stand().get()), "Testseite Karte 1355")
                    .knoepfe("Zählen", List.of(new MobilSeite.Knopf("plus", "1", "+1")))
                    .bauen();
        }

        @Override
        public MobilAntwort handle(String aktion, String wert) {
            if (!"plus".equals(aktion)) {
                return MobilAntwort.fehler("Unbekannt");
            }
            stand().incrementAndGet();
            return MobilAntwort.ok("gezählt");
        }
    }

    /**
     * Die beiden Testseiten — bewusst OHNE {@code @TestConfiguration}/{@code @Configuration} und
     * nur ueber {@code @Import} oben eingebunden.
     *
     * <p>Mit {@code @TestConfiguration} fand der Komponenten-Scan der root-Anwendung die Klasse
     * (sie liegt unter {@code ch.plaintext}) und legte die Zaehlseite in JEDEN Testkontext dieses
     * Pakets. {@code WatchHandyLinkPlaywrightIT} sah dann eine zweite Seite im Umlauf und wurde
     * dreimal rot ('1/1' statt einer leeren Position), gemessen 01.10.2026. Eine Klasse ohne
     * Stereotyp sieht der Scan nicht; {@code @Import} nimmt ihre {@code @Bean}-Methoden trotzdem.</p>
     */
    static class TestSeiten {
        @Bean
        ZaehlSeite zaehlSeite() {
            return new ZaehlSeite("zaehler", true);
        }

        @Bean
        ZaehlSeite gesperrteSeite() {
            return new ZaehlSeite("gesperrt", false);
        }
    }

    /** Laeuft in jedem Dokument: sammelt CSP-Verletzungen, bevor ein Seitenskript laeuft. */
    private static final String CSP_SAMMLER = """
            window.__csp = [];
            document.addEventListener('securitypolicyviolation',
                e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));
            """;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "mobilseiteplaywrightit");
    }

    @LocalServerPort
    int port;
    @Autowired
    MyUserRepository userRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    ISettingsService settingsService;

    private Playwright playwright;
    private Browser browser;

    @BeforeAll
    void aufbau() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "Chromium not installed: " + e.getMessage());
        }
        if (userRepository.findByUsername(BENUTZER) == null) {
            MyUserEntity u = new MyUserEntity();
            u.setUsername(BENUTZER);
            u.setPassword(passwordEncoder.encode(PASSWORT));
            u.addRole("USER");
            u.addRole("ADMIN");
            u.setMandat("default");
            userRepository.save(u);
        }
        settingsService.setSetting(SettingsKeys.APP_OWNHOST, "default", basis(), "STRING", "IT");
    }

    @AfterAll
    void abbau() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    // ═══════════════════════════════════════════════════════════════ Laden

    @Test
    @DisplayName("Die Seite laedt mit hoechstens 3 Anfragen, ohne Konsolenfehler und ohne CSP-Verletzung")
    void laedtSauber() {
        try (BrowserContext ctx = angemeldeterKontext()) {
            Page p = ctx.newPage();
            List<String> fehler = konsolenfehler(p);
            List<String> anfragen = new ArrayList<>();
            p.onRequest(r -> {
                if (!r.url().endsWith("/favicon.ico")) {
                    anfragen.add(r.url());
                }
            });

            Response r = p.navigate(url(SEITE));
            p.waitForLoadState();
            assertNotNull(r);
            assertEquals(200, r.status(), "HTTP " + r.status() + " auf " + SEITE);
            assertEquals("Zähler", p.locator(".w-head h1").innerText().trim());
            assertTrue(p.locator(".w-pos").innerText().trim().matches("\\d+/\\d+"),
                    "Position leer: '" + p.locator(".w-pos").innerText() + "'");
            assertEquals(1, p.locator(".w-nav a.w-aktion").count(), "Weiter-Knopf fehlt");

            String csp = r.headers().get("content-security-policy");
            assertNotNull(csp, "Kein CSP-Header auf " + SEITE);
            assertTrue(csp.contains("default-src 'self'") && csp.contains("script-src 'self'"),
                    "CSP ist nicht die des Hauses: " + csp);
            assertFalse(csp.contains("unsafe-eval"), "CSP erlaubt eval: " + csp);
            // Strenger als der Header des Hauses: diese Seiten brauchen kein unsafe-inline.
            assertFalse(csp.contains("unsafe-inline"), "CSP erlaubt Inline-Skript/-Stil: " + csp);

            assertTrue(anfragen.size() <= 3, "Mehr als 3 Anfragen: " + anfragen);
            // watch.css wirkt wirklich (sonst laege alles als Standardtext da und die Zahl oben
            // sagte nichts ueber die Seite).
            assertEquals("block", p.evaluate("() => getComputedStyle(document.querySelector('.w-chip')).display"));

            p.waitForTimeout(300);
            assertEquals(List.of(), fehler, "Konsolenfehler auf " + SEITE);
            assertEquals(List.of(), p.evaluate("() => window.__csp"), "CSP-Verletzungen auf " + SEITE);

            // Gegenprobe: der Sammler hoert wirklich zu.
            p.evaluate("() => console.error('Gegenprobe Karte 1355')");
            p.waitForTimeout(100);
            assertEquals(1, fehler.size(), "Der Konsolen-Sammler hat den eingeschleusten Fehler nicht gesehen");
        }
    }

    @Test
    @DisplayName("Karte 1387: home und elemente laden mit hoechstens 3 Anfragen, ohne Konsolenfehler und CSP-Verletzung")
    void rootSeitenLadenSauber() {
        for (String seite : List.of("/watch/m/home", "/watch/m/elemente")) {
            try (BrowserContext ctx = angemeldeterKontext()) {
                Page p = ctx.newPage();
                List<String> fehler = konsolenfehler(p);
                List<String> anfragen = new ArrayList<>();
                p.onRequest(r -> {
                    if (!r.url().endsWith("/favicon.ico")) {
                        anfragen.add(r.url());
                    }
                });
                Response r = p.navigate(url(seite));
                p.waitForLoadState();
                assertNotNull(r);
                assertEquals(200, r.status(), "HTTP " + r.status() + " auf " + seite);
                assertEquals(1, p.locator(".w-wrap").count(), seite + ": keine Uhr");
                assertTrue(anfragen.size() <= 3, seite + ": mehr als 3 Anfragen: " + anfragen);
                assertEquals(0, p.locator("script[src*='primefaces'], script[src*='jquery']").count(),
                        seite + ": PrimeFaces/jQuery geladen");
                String csp = r.headers().get("content-security-policy");
                assertFalse(csp == null || csp.contains("unsafe"), seite + ": CSP " + csp);
                p.waitForTimeout(300);
                assertEquals(List.of(), fehler, "Konsolenfehler auf " + seite);
                assertEquals(List.of(), p.evaluate("() => window.__csp"), "CSP-Verletzungen auf " + seite);
            }
        }
    }

    @Test
    @DisplayName("Karte 1387: ein geaendertes Zeitfeld speichert per fetch beim Aendern — Felder kommen bei der Seite an")
    void feldSpeichertBeimAendern() {
        try (BrowserContext ctx = angemeldeterKontext()) {
            Page p = ctx.newPage();
            List<String> fehler = konsolenfehler(p);
            p.navigate(url("/watch/m/elemente"));
            p.waitForLoadState();
            p.evaluate("() => { window.__marke = 1387; }");
            assertEquals(0, p.locator("noscript button:visible").count(), "Mit JavaScript gibt es keinen OK-Knopf");

            // Genau EIN change (Karte 1421): fill() loest bei type=time selbst schon change aus. Ein zweites
            // dispatchEvent traf, wenn die erste Antwort schneller war, bereits das NEU gerenderte Feld
            // (die Beispielseite rendert immer 08:00) und schickte 08:00 hinterher — rot in root#289 und
            // lokal, gruen nur, wenn die Antwort langsamer kam.
            p.locator("input[name='f-von']").evaluate(
                    "e => { e.value = '08:30'; e.dispatchEvent(new Event('change', {bubbles: true})); }");
            p.waitForFunction("() => document.getElementById('m-meldung').textContent.includes('Übernommen')");

            assertEquals("Übernommen: 08:30–09:45", p.locator("#m-meldung").innerText().trim(),
                    "Die Seite hat die Felder nicht so bekommen, wie sie im Formular standen");
            assertEquals(1387, ((Number) p.evaluate("() => window.__marke")).intValue(),
                    "Die Seite wurde neu geladen statt per fetch aktualisiert");
            assertEquals(List.of(), fehler, "Konsolenfehler nach der Aenderung");
        }
    }

    @Test
    @DisplayName("Karte 1387: ohne JavaScript traegt das Zeitfeld einen OK-Knopf, und der speichert")
    void feldOhneJavaScript() {
        try (BrowserContext ctx = angemeldeterKontext(false)) {
            Page p = ctx.newPage();
            p.navigate(url("/watch/m/elemente"));
            p.waitForLoadState();
            p.locator("input[name='f-bis']").fill("10:00");
            Locator ok = p.locator("form[data-mobil-auto] button[type=submit]");
            assertEquals(1, ok.count(), "Ohne JavaScript fehlt der OK-Knopf im Formular der Felder: "
                    + p.locator("main").innerHTML());
            ok.click();
            p.waitForLoadState();
            assertTrue(p.url().endsWith("/watch/m/elemente"), "Nicht auf die Seite zurueck: " + p.url());
            assertEquals("Übernommen: 08:00–10:00", p.locator("#m-meldung").innerText().trim());
        }
    }

    // ═══════════════════════════════════════════════════════════════ Aktionen

    @Test
    @DisplayName("+1 laeuft per fetch: kein Neuladen, Zaehler im Dienst +1, Statuszeile sagt es")
    void aktionOhneNeuladen() {
        try (BrowserContext ctx = angemeldeterKontext()) {
            Page p = ctx.newPage();
            List<String> fehler = konsolenfehler(p);
            p.navigate(url(SEITE));
            p.waitForLoadState();
            int vorher = stand();

            p.evaluate("() => { window.__marke = 1355; }");
            p.getByText("+1").click();
            p.locator("#m-meldung").waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));

            assertEquals(vorher + 1, stand(), "Der Druck hat im Dienst nichts gezaehlt");
            assertEquals(1355, ((Number) p.evaluate("() => window.__marke")).intValue(),
                    "Die Marke im window ist weg — die Seite wurde neu geladen statt per fetch aktualisiert");
            assertEquals("gezählt", p.locator("#m-meldung").innerText().trim());
            assertEquals(String.valueOf(vorher + 1), p.locator(".w-value").innerText().trim(),
                    "Die Anzeige zeigt den neuen Stand nicht");
            assertEquals(List.of(), fehler, "Konsolenfehler nach der Aktion");
            assertEquals(List.of(), p.evaluate("() => window.__csp"), "CSP-Verletzungen nach der Aktion");

            // Zweimal hintereinander: der ausgetauschte Inhalt ist wieder bedienbar.
            p.getByText("+1").click();
            p.waitForFunction("n => document.querySelector('.w-value').innerText.trim() === String(n)", vorher + 2);
            assertEquals(vorher + 2, stand());
        }
    }

    @Test
    @DisplayName("Ohne JavaScript: dasselbe Formular, 303 zurueck, Zaehler +1")
    void ohneJavaScript() {
        try (BrowserContext ctx = angemeldeterKontext(false)) {
            Page p = ctx.newPage();
            p.navigate(url(SEITE));
            p.waitForLoadState();
            int vorher = stand();
            p.getByText("+1").click();
            p.waitForLoadState();

            assertEquals(vorher + 1, stand(), "Ohne JavaScript hat der Knopf nichts getan");
            assertTrue(p.url().endsWith(SEITE), "Nicht auf die Seite zurueck: " + p.url());
            assertEquals("gezählt", p.locator("#m-meldung").innerText().trim());
        }
    }

    // ═══════════════════════════════════════════════════════════════ Sicherheit

    @Test
    @DisplayName("Ohne Anmeldung: Seite fuehrt zur Anmeldung, Aktion wirkt nicht")
    void ohneAnmeldung() {
        try (BrowserContext ctx = frischerKontext()) {
            Page p = ctx.newPage();
            p.navigate(url(SEITE));
            p.waitForLoadState();
            assertTrue(p.url().contains("login"), "Ohne Anmeldung nicht auf der Anmeldung: " + p.url());
            assertEquals(0, p.locator(".w-wrap").count(), "Seite ohne Anmeldung sichtbar");

            int vorher = stand();
            APIResponse post = ctx.request().post(url(AKTION), RequestOptions.create()
                    .setForm(FormData.create().set("wert", "1")).setMaxRedirects(0));
            assertTrue(post.status() == 403 || post.status() == 302,
                    "POST ohne Anmeldung: HTTP " + post.status());
            assertEquals(vorher, stand(), "Ein POST ohne Anmeldung hat gezaehlt");
        }
    }

    @Test
    @DisplayName("POST ohne CSRF-Token: 403, keine Wirkung — mit Token wirkt derselbe POST")
    void csrfPflicht() {
        try (BrowserContext ctx = angemeldeterKontext()) {
            Page p = ctx.newPage();
            p.navigate(url(SEITE));
            p.waitForLoadState();
            int vorher = stand();

            APIResponse ohne = ctx.request().post(url(AKTION), RequestOptions.create()
                    .setForm(FormData.create().set("wert", "1"))
                    .setHeader("Accept", "application/json").setMaxRedirects(0));
            assertEquals(403, ohne.status(), "POST ohne CSRF: HTTP " + ohne.status());
            assertEquals(vorher, stand(), "Ein POST ohne CSRF-Token hat gezaehlt");

            // Positivkontrolle: mit dem Token aus der Seite geht derselbe POST durch.
            String token = p.locator("input[name=_csrf]").first().inputValue();
            APIResponse mit = ctx.request().post(url(AKTION), RequestOptions.create()
                    .setForm(FormData.create().set("wert", "1").set("_csrf", token))
                    .setHeader("Accept", "application/json").setMaxRedirects(0));
            assertEquals(200, mit.status(), "POST mit CSRF: HTTP " + mit.status());
            assertTrue(mit.text().contains("\"ok\":true"), "Antwort: " + mit.text());
            assertEquals(vorher + 1, stand());
        }
    }

    @Test
    @DisplayName("Karte 1387: ohne die Rollen der Watch-Einstellungen sind home und elemente 404 — auch fuer den Schalter")
    void rootSeitenFolgenDenRollen() {
        if (userRepository.findByUsername(GAST) == null) {
            MyUserEntity u = new MyUserEntity();
            u.setUsername(GAST);
            u.setPassword(passwordEncoder.encode(PASSWORT));
            u.addRole("GAST");
            u.setMandat("default");
            userRepository.save(u);
        }
        try (BrowserContext ctx = angemeldeterKontext(true, GAST)) {
            Page p = ctx.newPage();
            for (String seite : List.of("/watch/m/home", "/watch/m/elemente", "/watch/home.html")) {
                Response r = p.navigate(url(seite));
                assertEquals(404, r.status(), seite + " antwortet einem Benutzer ohne die Rollen mit " + r.status());
                assertEquals(0, p.locator(".w-wrap").count(), seite + ": Uhr sichtbar");
            }
            // Ein gueltiges CSRF-Token derselben Sitzung — von der Testseite, die allen offen steht.
            p.navigate(url(SEITE));
            String token = p.locator("input[name=_csrf]").first().inputValue();
            APIResponse post = ctx.request().post(url("/watch/m/elemente/schalte"), RequestOptions.create()
                    .setForm(FormData.create().set("wert", "zaehler").set("_csrf", token)).setMaxRedirects(0));
            assertEquals(404, post.status(), "Der Schalter wirkt ohne die Rollen: HTTP " + post.status());
        }
        // Positivkontrolle: derselbe Aufruf mit den Rollen zeigt die Seite.
        try (BrowserContext ctx = angemeldeterKontext()) {
            Page p = ctx.newPage();
            assertEquals(200, p.navigate(url("/watch/m/elemente")).status());
        }
    }

    @Test
    @DisplayName("Eine gesperrte Seite antwortet 404 — auch auf eine Aktion")
    void gesperrteSeite() {
        try (BrowserContext ctx = angemeldeterKontext()) {
            Page p = ctx.newPage();
            Response r = p.navigate(url("/watch/m/gesperrt"));
            assertEquals(404, r.status());
            p.navigate(url(SEITE));
            String token = p.locator("input[name=_csrf]").first().inputValue();
            APIResponse post = ctx.request().post(url("/watch/m/gesperrt/plus"), RequestOptions.create()
                    .setForm(FormData.create().set("wert", "1").set("_csrf", token)).setMaxRedirects(0));
            assertEquals(404, post.status());
        }
    }

    @Test
    @DisplayName("Handy-Link: die Seite ist ohne Anmeldung erreichbar und bedienbar, nach dem Abschalten nicht mehr")
    void handyLink() {
        String link;
        BrowserContext admin = angemeldeterKontext();
        try {
            Page maske = admin.newPage();
            maske.navigate(url("/watch-einstellungen.html"));
            maske.waitForLoadState();
            link = erzeuge(maske);

            try (BrowserContext telefon = frischerKontext()) {
                Page p = telefon.newPage();
                p.navigate(link);
                p.waitForLoadState();
                assertFalse(p.url().contains("login"), "Link fuehrte zur Anmeldung: " + p.url());

                Response r = p.navigate(url(SEITE));
                assertEquals(200, r.status(), "Mit Handy-Link: HTTP " + r.status());
                assertEquals("Zähler", p.locator(".w-head h1").innerText().trim());
                int vorher = stand();
                p.getByText("+1").click();
                p.waitForFunction("n => document.querySelector('.w-value').innerText.trim() === String(n)", vorher + 1);
                assertEquals(vorher + 1, stand(), "Mit Handy-Link hat der Knopf nichts gezaehlt");

                // Link abschalten — die schon offene Sitzung auf dem Telefon muss beim naechsten
                // Aufruf sterben, auch auf dieser Seite und auch bei einer Aktion.
                maske.locator("#fm\\:btnAbschalten").click();
                maske.locator("#fm\\:btnAbschalten").waitFor(
                        new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED).setTimeout(30_000));

                int nachher = stand();
                Response post = p.waitForResponse(a -> a.url().endsWith(AKTION),
                        () -> p.getByText("+1").click());
                assertEquals(403, post.status(),
                        "Die Aktion nach dem Abschalten antwortete mit HTTP " + post.status());
                p.waitForLoadState();
                p.waitForTimeout(500);
                assertEquals(nachher, stand(), "Nach dem Abschalten des Links zaehlt die Aktion noch");
                // Die Sitzung ist beendet: die Seite fuehrt zur Anmeldung, nicht mehr zum Zaehler.
                p.navigate(url(SEITE));
                p.waitForLoadState();
                assertEquals(0, p.locator("#m-inhalt").count(),
                        "Nach dem Abschalten ist die Seite noch da: " + p.url());
            }
        } finally {
            admin.close();
        }
    }

    // ═══════════════════════════════════════════════════════════════ Werkzeug

    /**
     * Summe ueber alle Benutzer: es gibt in dieser Klasse nur einen, und die Summe haengt nicht
     * davon ab, ob {@code getUser()} fuer Passwort- und Token-Sitzung dieselbe Schreibweise
     * liefert. Genau darum wird auch der POST ohne Anmeldung gegen die Summe geprueft.
     */
    private static int stand() {
        return STAND.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    private List<String> konsolenfehler(Page p) {
        List<String> fehler = new ArrayList<>();
        p.onConsoleMessage(m -> {
            if ("error".equals(m.type())) {
                fehler.add(m.text());
            }
        });
        p.onPageError(fehler::add);
        return fehler;
    }

    private BrowserContext frischerKontext() {
        BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(390, 844).setIsMobile(true).setHasTouch(true)
                .setLocale("de-CH").setTimezoneId("Europe/Zurich"));
        ctx.addInitScript(CSP_SAMMLER);
        return ctx;
    }

    private BrowserContext angemeldeterKontext() {
        return angemeldeterKontext(true);
    }

    private BrowserContext angemeldeterKontext(boolean javaScript) {
        return angemeldeterKontext(javaScript, BENUTZER);
    }

    private BrowserContext angemeldeterKontext(boolean javaScript, String benutzer) {
        BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(390, 844).setIsMobile(true).setHasTouch(true)
                .setJavaScriptEnabled(javaScript)
                .setLocale("de-CH").setTimezoneId("Europe/Zurich"));
        ctx.addInitScript(CSP_SAMMLER);
        // Anmelden mit JavaScript: die Anmeldeseite ist nicht Gegenstand dieser Klasse.
        try (BrowserContext anmeldung = browser.newContext()) {
            Page p = anmeldung.newPage();
            p.navigate(url("/login.html"));
            p.waitForSelector("#username");
            p.fill("#username", benutzer);
            p.fill("#password", PASSWORT);
            p.locator("#password").press("Enter");
            p.waitForLoadState();
            assertFalse(p.url().contains("login"), "Login schlug fehl: " + p.url());
            ctx.addCookies(anmeldung.cookies());
        }
        return ctx;
    }

    /** Wie WatchHandyLinkPlaywrightIT#erzeuge: auf den NEUEN Link warten, nicht auf das Feld. */
    private String erzeuge(Page maske) {
        Locator feld = maske.locator("#fm\\:linkFeld");
        String vorher = feld.count() > 0 ? feld.inputValue() : "";
        maske.click("#fm\\:btnErzeugen");
        String link = vorher;
        long ende = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < ende) {
            if (feld.count() > 0) {
                link = feld.inputValue();
                if (!link.equals(vorher)) {
                    break;
                }
            }
            maske.waitForTimeout(200);
        }
        assertNotEquals(vorher, link, "Kein neuer Handy-Link erzeugt");
        assertTrue(link.startsWith(basis() + "/nosec/watch?t="), "Kein vollstaendiger Link");
        return link;
    }

    private String url(String pfad) {
        return basis() + pfad;
    }

    private String basis() {
        return "http://localhost:" + port;
    }
}

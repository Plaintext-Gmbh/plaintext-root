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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1312 — das Muster der Watch-Seiten, bei 390px <b>gemessen</b> statt begutachtet.
 *
 * <p><b>Der Messaufbau.</b> Viewport 390x844, die logische Breite eines iPhone 12/13/14 — die
 * Breite von Daniels Bildschirmfotos vom 20.09.2026. Gemessen werden, wie in Karte 1286,
 * <b>Textrechtecke ueber einen {@code Range}</b> und nicht Elementrechtecke: die Aussage
 * „steht im Knopf" ist eine ueber den Text, den man sieht. Ein {@code <div>} kann ohne Weiteres
 * im Knopfrechteck liegen, waehrend sein Text darueber hinauslaeuft — und umgekehrt.
 *
 * <p><b>Was gemessen wird.</b> Drei Punkte, alle zentral in plaintext-root-watch geloest:
 * <ol>
 *   <li><b>Abstand ueber dem Loeschknopf.</b> Die {@code .w-row} mit Betrag und {@code ×} klebte
 *       an der Zeile darueber. Verlangt: vorher 0 px, nachher mehr.</li>
 *   <li><b>Der Hinweis „lang druecken: Uebersicht" liegt IM Knopf.</b> Vorher ein eigenes
 *       Element unterhalb — also ausserhalb des Knopfrechtecks. Dieser Punkt steht in
 *       {@code frame.xhtml} und gilt darum auf <b>jeder</b> Watch-Seite; er wird hier auf
 *       zwei Seiten nachgemessen.</li>
 *   <li><b>Kategorie und Dauer liegen im Stop-Knopf.</b> Vorher drei getrennte Elemente
 *       uebereinander.</li>
 * </ol>
 *
 * <p><b>Warum die Gegenproben hier stehen und nicht daneben.</b> „Der Text liegt im Knopf" ist
 * wertlos, solange nicht gezeigt ist, dass dieselbe Messung ein „liegt nicht im Knopf"
 * ueberhaupt findet. Jede der drei Aussagen hat darum ihre Kontrolle, die den Stand DAVOR per
 * {@code addStyleTag} wiederherstellt und verlangt, dass die Messung dann das Gegenteil meldet.
 *
 * <p><b>Was hier NICHT gemessen wird.</b> Die fuenf Watch-Seiten in plaintext-app (zeit,
 * kalorien, alkohol, challenge, kalender). Sie laufen gegen ein <i>released</i> root-Jar; ihre
 * Messung gehoert in den zweiten Schritt nach Release und Versionsbump. Die Punkte 1 und 2
 * brauchen dort keine Aenderung — sie stecken in {@code watch.css} und {@code frame.xhtml} und
 * kommen mit dem Bump von selbst mit. Punkt 3 kostet je Seite eine Zeile: {@code <w:aktion .../>}.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.docker.compose.enabled=false"
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WatchMusterPlaywrightIT {

    private static final Logger LOG = LoggerFactory.getLogger(WatchMusterPlaywrightIT.class);

    private static final String BENUTZER = "pw-muster";
    private static final String PASSWORT = "Playwright-2026!";

    /** iPhone 12/13/14, logische Pixel — die Breite von Daniels Bildschirmfotos. */
    private static final int BREITE = 390;
    private static final int HOEHE = 844;

    /** Subpixel-Rundung zaehlt nicht. */
    private static final double EPSILON = 0.75;

    /** Sichtbare Luft zwischen zwei gestapelten Zeilen; --w-zeilenluft steht auf 10px. */
    private static final double MINDESTLUFT = 6.0;

    /** Die Schrift des handelnden Knopfes: --w-schrift-knopf = 1.5rem von 26px = 39px. */
    private static final double MINDESTSCHRIFT_KNOPF = 36.0;

    /** Die Zahl im Knopf: --w-aktion-gross = 2.2rem von 26px = 57px. */
    private static final double MINDESTSCHRIFT_ZAHL = 45.0;

    /** Die zwei Watch-Seiten, die es in root gibt. Beide binden denselben Rahmen ein. */
    private static final List<String> SEITEN = List.of("/watch/home.html", "/watch/elemente.html");

    /** Die Galerieseite — nur sie zeigt Eintragszeile und handelnden Knopf. */
    private static final String GALERIE = "/watch/elemente.html";

    /** Punkt 1, Stand davor: es gab keine Regel fuer zwei gestapelte Zeilen. */
    private static final String STAND_DAVOR_ZEILEN = """
            .w-row + .w-row { margin-top: 0 !important; }
            """;

    /** Punkt 2, Stand davor: der Hinweis war ein eigenes Element UNTER dem Knopf. */
    private static final String STAND_DAVOR_HINWEIS = """
            .w-aktion-hinweis {
              position: absolute !important;
              top: 100% !important;
              left: 0 !important;
              right: 0 !important;
              margin-top: 6px !important;
            }
            """;

    /**
     * Punkt 3, Stand davor: der Knopf war ein Element unter den beiden Angaben statt die
     * Flaeche um sie herum. Das Eingabefeld faellt damit aus der Deckung zurueck in den Fluss.
     */
    private static final String STAND_DAVOR_AKTION = """
            .w-aktion-flaeche {
              position: static !important;
              inset: auto !important;
              width: 100% !important;
              height: auto !important;
              min-height: 56px !important;
              opacity: 1 !important;
            }
            """;

    /**
     * Misst den Knopf der Navigation: das Rechteck des echten Eingabefeldes (die Trefferflaeche)
     * und die TEXTrechtecke von Zeichen und Hinweis.
     */
    private static final String SKRIPT_NAV = """
            () => {
              const textRect = (el) => {
                if (!el) { return null; }
                const r = document.createRange();
                r.selectNodeContents(el);
                const b = r.getBoundingClientRect();
                return { x: b.x, y: b.y, w: b.width, h: b.height };
              };
              // Der Stand NACH Karte 1312: .w-aktion ist die Flaeche, das Eingabefeld deckt
              // sie ab, Zeichen und Hinweis liegen darin.
              let kasten = document.querySelector('.w-nav .w-aktion');
              let feld = kasten ? kasten.querySelector('.w-aktion-flaeche') : null;
              let wort = kasten ? kasten.querySelector('.w-aktion-wort') : null;
              let hinweis = kasten ? kasten.querySelector('.w-aktion-hinweis') : null;

              // Der Stand DAVOR: ein .w-btn mit dem Zeichen als value und der Hinweis als
              // eigenes Element daneben. Die Rueckfallebene steht hier, damit DIESELBE Messung
              // beide Staende lesen kann — ohne sie meldete ein Lauf gegen den alten Stand nur
              // "Element nicht gefunden" und lieferte keine Zahl zum Vergleichen. Sie macht den
              // Test ausserdem schaerfer: wer die Aenderung zurueckdreht, bekommt "Hinweis
              // ausserhalb des Knopfes" statt eines Suchfehlers.
              if (!feld) {
                feld = document.querySelector('.w-nav .w-btn');
                wort = feld;
                hinweis = document.querySelector('.w-nav-hinweis');
              }
              if (!feld) { return null; }
              const f = feld.getBoundingClientRect();
              // Ein <input> hat keinen Textinhalt; sein Text ist das value. Dafuer gibt es kein
              // Range — dann gilt das Elementrechteck, und das ist hier zulaessig, weil die
              // Aussage lautet "das Zeichen steht im Knopf" und das Zeichen IST der Knopf.
              const wortRect = (wort && wort.tagName !== 'INPUT') ? textRect(wort)
                  : { x: f.x, y: f.y, w: f.width, h: f.height };
              return {
                flaeche: { x: f.x, y: f.y, w: f.width, h: f.height },
                wort: wortRect,
                wortGroesse: wort ? parseFloat(getComputedStyle(wort).fontSize) : 0,
                hinweis: textRect(hinweis),
                hinweisText: (hinweis || {}).textContent || ''
              };
            }
            """;

    /**
     * Misst die Luft ueber jedem Loeschknopf: die Zeile, in der er steht, gegen die Zeile
     * darueber. Elementrechtecke genuegen hier — die Aussage ist eine ueber zwei Kaesten,
     * nicht ueber Text.
     */
    private static final String SKRIPT_ZEILENLUFT = """
            () => {
              const aus = [];
              document.querySelectorAll('.w-btn-small').forEach(knopf => {
                const zeile = knopf.closest('.w-row');
                if (!zeile) { return; }
                const vorher = zeile.previousElementSibling;
                if (!vorher || !vorher.classList.contains('w-row')) { return; }
                const a = vorher.getBoundingClientRect();
                const b = zeile.getBoundingClientRect();
                aus.push({ luft: b.top - a.bottom, knopf: knopf.value || knopf.textContent });
              });
              return aus;
            }
            """;

    /**
     * Misst jeden handelnden Knopf, der eine Zahl traegt: Trefferflaeche gegen die
     * TEXTrechtecke von Kategorie und Zahl.
     */
    private static final String SKRIPT_AKTION = """
            () => {
              const textRect = (el) => {
                if (!el) { return null; }
                const r = document.createRange();
                r.selectNodeContents(el);
                const b = r.getBoundingClientRect();
                return { x: b.x, y: b.y, w: b.width, h: b.height };
              };
              const aus = [];
              document.querySelectorAll('.w-aktion').forEach(kasten => {
                const gross = kasten.querySelector('.w-aktion-gross');
                const oben = kasten.querySelector('.w-aktion-oben');
                if (!gross || !oben) { return; }
                const feld = kasten.querySelector('.w-aktion-flaeche');
                if (!feld) { return; }
                const f = feld.getBoundingClientRect();
                aus.push({
                  flaeche: { x: f.x, y: f.y, w: f.width, h: f.height },
                  oben: textRect(oben),
                  obenText: oben.textContent.trim(),
                  gross: textRect(gross),
                  grossText: gross.textContent.trim(),
                  grossGroesse: parseFloat(getComputedStyle(gross).fontSize),
                  obenGroesse: parseFloat(getComputedStyle(oben).fontSize)
                });
              });
              return aus;
            }
            """;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "watchmusterplaywrightit");
    }

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

    private String url(String pfad) {
        return "http://localhost:" + port + pfad;
    }

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
            u.setMandat("default");
            userRepository.save(u);
        }
        context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(BREITE, HOEHE)
                .setDeviceScaleFactor(3)
                .setIsMobile(true)
                .setHasTouch(true)
                .setLocale("de-CH")
                .setTimezoneId("Europe/Zurich"));
        page = context.newPage();
        page.navigate(url("/login.html"));
        page.waitForSelector("#username");
        page.fill("#username", BENUTZER);
        page.fill("#password", PASSWORT);
        page.locator("#password").press("Enter");
        page.waitForLoadState();
        assertFalse(page.url().contains("login"), "Login als " + BENUTZER + " schlug fehl: " + page.url());
    }

    @AfterAll
    void abbau() {
        if (context != null) {
            context.close();
        }
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    // ══════════════════════════════════════════════════════ Punkt 2 — der Hinweis im Knopf

    @Test
    @DisplayName("390px: der Hinweis liegt IM Knopfrechteck — auf beiden Watch-Seiten")
    void hinweisLiegtImKnopf() {
        for (String seite : SEITEN) {
            oeffne(seite);
            Map<String, Object> nav = nav(seite);
            Rechteck flaeche = rechteck(nav.get("flaeche"), "Trefferflaeche");
            Rechteck hinweis = rechteck(nav.get("hinweis"), "Hinweistext");
            Rechteck wort = rechteck(nav.get("wort"), "Zeichen");
            double schrift = zahl(nav.get("wortGroesse"));

            LOG.info("Karte 1312/Punkt 2 — {}: Flaeche {}, Hinweis {}, Zeichen {} bei {}px",
                    seite, flaeche, hinweis, wort, schrift);
            assertFalse(String.valueOf(nav.get("hinweisText")).isBlank(),
                    seite + ": der Hinweis ist leer — dann misst dieser Test nichts.");

            assertTrue(liegtIn(hinweis, flaeche),
                    seite + ": der Hinweis liegt NICHT im Knopfrechteck. Hinweis " + hinweis
                            + ", Knopf " + flaeche + ". Genau so sah es auf Daniels Bild aus: "
                            + "eine graue Zeile unter dem Knopf, die wie eine Fussnote der Seite "
                            + "wirkt statt wie eine Eigenschaft des Knopfes.");
            assertTrue(liegtIn(wort, flaeche),
                    seite + ": schon das Zeichen liegt nicht im Knopfrechteck — " + wort + " in " + flaeche);
            assertTrue(schrift >= MINDESTSCHRIFT_KNOPF,
                    seite + ": die Knopfschrift steht auf " + schrift + "px, verlangt sind "
                            + MINDESTSCHRIFT_KNOPF + "px. Daniel: \"button schrift groesser\".");
            assertTrue(flaeche.h() >= 56 - EPSILON,
                    seite + ": die Trefferflaeche ist nur " + flaeche.h() + "px hoch (--w-tap = 56px).");
            assertTrue(flaeche.w() >= BREITE * 0.7,
                    seite + ": die Trefferflaeche ist nur " + flaeche.w() + "px breit — der Knopf "
                            + "sieht dann breiter aus, als er zu treffen ist.");
        }
    }

    @Test
    @DisplayName("Gegenprobe Punkt 2: am Stand davor liegt der Hinweis ausserhalb des Knopfes")
    void gegenprobeHinweis() {
        for (String seite : SEITEN) {
            oeffne(seite);
            Rechteck vorher = rechteck(nav(seite).get("hinweis"), "Hinweis mit Korrektur");
            Rechteck flaecheVorher = rechteck(nav(seite).get("flaeche"), "Flaeche");
            page.addStyleTag(new Page.AddStyleTagOptions().setContent(STAND_DAVOR_HINWEIS));
            page.waitForTimeout(150);
            Map<String, Object> nachher = nav(seite);
            Rechteck hinweis = rechteck(nachher.get("hinweis"), "Hinweis am Stand davor");
            Rechteck flaeche = rechteck(nachher.get("flaeche"), "Flaeche am Stand davor");

            LOG.info("Karte 1312/Punkt 2 — {}: mit Korrektur {} in {}, am Stand davor {} in {}",
                    seite, vorher, flaecheVorher, hinweis, flaeche);
            assertFalse(liegtIn(hinweis, flaeche),
                    seite + ": die Messung findet den alten Zustand nicht wieder — sie meldet "
                            + "auch dann \"im Knopf\", wenn der Hinweis unter dem Knopf steht. "
                            + "Dann belegt das gruene Ergebnis oben nichts.");
        }
    }

    // ══════════════════════════════════════════════════ Punkt 1 — Abstand ueber dem Loeschknopf

    @Test
    @DisplayName("390px: ueber dem Loeschknopf liegt Luft")
    void ueberDemLoeschknopfLiegtLuft() {
        oeffne(GALERIE);
        List<Double> luft = zeilenluft();
        LOG.info("Karte 1312/Punkt 1 — {}: Luft ueber dem Loeschknopf {}", GALERIE, luft);
        assertFalse(luft.isEmpty(),
                GALERIE + ": keine Zeile mit .w-btn-small unter einer zweiten .w-row gefunden — "
                        + "dann misst dieser Test nichts. Genau diese Anordnung hat Daniel "
                        + "fotografiert.");
        double kleinste = luft.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        assertTrue(kleinste >= MINDESTLUFT,
                "Zwischen der Zeile mit dem Loeschknopf und der Zeile darueber liegen nur "
                        + kleinste + " px (verlangt: " + MINDESTLUFT + " px). Alle Werte: " + luft);
    }

    @Test
    @DisplayName("Gegenprobe Punkt 1: am Stand davor faellt die Luft auf 0 px")
    void gegenprobeZeilenluft() {
        oeffne(GALERIE);
        double mitKorrektur = kleinsteLuft();
        page.addStyleTag(new Page.AddStyleTagOptions().setContent(STAND_DAVOR_ZEILEN));
        page.waitForTimeout(150);
        double ohneKorrektur = kleinsteLuft();
        LOG.info("Karte 1312/Punkt 1 — mit Korrektur {} px, am Stand davor {} px",
                mitKorrektur, ohneKorrektur);
        assertTrue(ohneKorrektur < 1.0,
                "Am Stand DAVOR liegen " + ohneKorrektur + " px Luft. Gemessen wurde vor der "
                        + "Aenderung 0.0 px — findet dieser Aufbau das nicht wieder, belegt das "
                        + "gruene Ergebnis oben nichts.");
        assertTrue(mitKorrektur - ohneKorrektur >= MINDESTLUFT,
                "Die Korrektur bringt nur " + (mitKorrektur - ohneKorrektur) + " px mehr Luft.");
    }

    // ══════════════════════════════════════════════ Punkt 3 — Kategorie und Dauer im Knopf

    @Test
    @DisplayName("390px: Kategorie und Dauer liegen im Knopfrechteck und sind gross")
    void kategorieUndDauerLiegenImKnopf() {
        oeffne(GALERIE);
        List<Map<String, Object>> knoepfe = aktionsknoepfe();
        assertFalse(knoepfe.isEmpty(),
                GALERIE + ": kein handelnder Knopf mit Kategorie und Zahl gefunden — dann misst "
                        + "dieser Test nichts.");
        for (Map<String, Object> k : knoepfe) {
            Rechteck flaeche = rechteck(k.get("flaeche"), "Trefferflaeche");
            Rechteck oben = rechteck(k.get("oben"), "Kategorie");
            Rechteck gross = rechteck(k.get("gross"), "Zahl");
            LOG.info("Karte 1312/Punkt 3 — Knopf: Flaeche {}, '{}' {} bei {}px, '{}' {} bei {}px",
                    flaeche, k.get("obenText"), oben, zahl(k.get("obenGroesse")),
                    k.get("grossText"), gross, zahl(k.get("grossGroesse")));

            assertTrue(liegtIn(oben, flaeche),
                    "Die Kategorie liegt NICHT im Knopfrechteck: " + oben + " in " + flaeche
                            + ". Vorher waren es drei getrennte Elemente uebereinander, von denen "
                            + "das dritte zufaellig ein Knopf war.");
            assertTrue(liegtIn(gross, flaeche),
                    "Die Dauer liegt NICHT im Knopfrechteck: " + gross + " in " + flaeche);
            assertTrue(zahl(k.get("grossGroesse")) >= MINDESTSCHRIFT_ZAHL,
                    "Die Zahl im Knopf steht auf " + zahl(k.get("grossGroesse")) + "px, verlangt "
                            + "sind " + MINDESTSCHRIFT_ZAHL + "px. Daniel: \"im button selber und "
                            + "groesser\".");
            assertTrue(flaeche.h() >= 56 - EPSILON,
                    "Die Trefferflaeche des Knopfes ist nur " + flaeche.h() + "px hoch.");
        }
    }

    @Test
    @DisplayName("Gegenprobe Punkt 3: faellt das Eingabefeld aus der Deckung, liegen beide draussen")
    void gegenprobeAktionsknopf() {
        oeffne(GALERIE);
        assertFalse(aktionsknoepfe().isEmpty(), "nichts zu messen");
        page.addStyleTag(new Page.AddStyleTagOptions().setContent(STAND_DAVOR_AKTION));
        page.waitForTimeout(150);
        List<Map<String, Object>> knoepfe = aktionsknoepfe();
        assertFalse(knoepfe.isEmpty(), "nach der Kontrolle nichts mehr zu messen");
        for (Map<String, Object> k : knoepfe) {
            Rechteck flaeche = rechteck(k.get("flaeche"), "Trefferflaeche am Stand davor");
            Rechteck oben = rechteck(k.get("oben"), "Kategorie am Stand davor");
            Rechteck gross = rechteck(k.get("gross"), "Zahl am Stand davor");
            LOG.info("Karte 1312/Punkt 3, Stand davor — Flaeche {}, Kategorie {}, Zahl {}",
                    flaeche, oben, gross);
            assertFalse(liegtIn(oben, flaeche) || liegtIn(gross, flaeche),
                    "Die Messung meldet \"im Knopf\", obwohl der Knopf unter den beiden Angaben "
                            + "steht — dann kann sie den gemeldeten Fehler gar nicht finden.");
        }
    }

    @Test
    @DisplayName("Der Grossknopf loest wirklich aus — ein Knopf, der nur aussieht wie einer, ist keiner")
    void derGrossknopfLoestAus() {
        oeffne(GALERIE);
        // Die Elementgalerie schreibt jede Handlung in ihre Karte "Zuletzt". Vor dem Tippen
        // steht dort etwas anderes — sonst belegte der Text danach nichts.
        String vorher = page.locator("#el .w-card .w-value").first().innerText().trim();
        page.locator(".w-aktion:has(.w-aktion-gross) .w-aktion-flaeche").first().click();
        page.waitForLoadState();
        String nachher = page.locator("#el .w-card .w-value").first().innerText().trim();

        LOG.info("Karte 1312 — Grossknopf: 'Zuletzt' vorher '{}', nachher '{}'", vorher, nachher);
        assertTrue(nachher.contains("Stop (gross)"),
                "Der Knopf des Tags w:aktion hat nichts ausgeloest: 'Zuletzt' steht auf '"
                        + nachher + "'. Das durchsichtige Eingabefeld ist der EINZIGE Weg, auf "
                        + "dem ein Tipp beim Bean ankommt — und die Methode kommt ueber ein "
                        + "Tag-File-Attribut (action=\"#{aktion}\"). Faellt das aus, sieht der "
                        + "Knopf richtig aus und tut nichts, auf allen sieben Seiten.");
        assertFalse(vorher.equals(nachher),
                "'Zuletzt' stand schon vorher auf demselben Wert — dann belegt der Text nichts.");
    }

    // ═══════════════════════════════════════════════════════════════════════ Werkzeug

    private void oeffne(String pfad) {
        page.navigate(url(pfad));
        page.waitForLoadState();
        assertTrue(page.locator(".w-wrap").count() > 0,
                "Auf " + pfad + " gibt es keinen .w-wrap — die Seite hat geantwortet, zeigt aber "
                        + "keine Uhr. Ein Status 200 belegt hier nichts (Karten 1243, 1248).");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nav(String seite) {
        Object roh = page.evaluate(SKRIPT_NAV);
        assertNotNull(roh, seite + ": kein Navigationsknopf gefunden (.w-nav .w-aktion) — der "
                + "Rahmen rendert ihn auf jeder Watch-Seite.");
        return (Map<String, Object>) roh;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> aktionsknoepfe() {
        List<Object> roh = (List<Object>) page.evaluate(SKRIPT_AKTION);
        return roh.stream().map(o -> (Map<String, Object>) o).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Double> zeilenluft() {
        List<Object> roh = (List<Object>) page.evaluate(SKRIPT_ZEILENLUFT);
        return roh.stream().map(o -> zahl(((Map<String, Object>) o).get("luft"))).toList();
    }

    private double kleinsteLuft() {
        return zeilenluft().stream().mapToDouble(Double::doubleValue).min().orElse(-1);
    }

    private static Rechteck rechteck(Object roh, String was) {
        assertNotNull(roh, was + " nicht gefunden");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) roh;
        return new Rechteck(zahl(m.get("x")), zahl(m.get("y")), zahl(m.get("w")), zahl(m.get("h")));
    }

    private static double zahl(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    /** Liegt {@code innen} vollstaendig in {@code aussen}? */
    private static boolean liegtIn(Rechteck innen, Rechteck aussen) {
        return innen.x() >= aussen.x() - EPSILON
                && innen.y() >= aussen.y() - EPSILON
                && innen.x() + innen.w() <= aussen.x() + aussen.w() + EPSILON
                && innen.y() + innen.h() <= aussen.y() + aussen.h() + EPSILON;
    }

    private record Rechteck(double x, double y, double w, double h) {
        @Override
        public String toString() {
            return "[x=%.1f y=%.1f b=%.1f h=%.1f]".formatted(x, y, w, h);
        }
    }
}

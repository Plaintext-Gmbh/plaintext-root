/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.web.resource.RessourcenStand;
import com.microsoft.playwright.APIResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1311 — <b>zwei Abrufe mit einer Aenderung dazwischen</b>.
 *
 * <p><b>Warum ein Abruf nichts belegt.</b> Am 20.09.2026 lieferte PROD auf
 * {@code /jakarta.faces.resource/watch.css.html?ln=watch} den richtigen Inhalt
 * ({@code --w-schrift: 26px}) mit {@code cache-control: max-age=604800}. Daniel sah trotzdem
 * den Stand von vorgestern. Ein einzelner Abruf haette also gesagt: alles in Ordnung. Die
 * Aussage, um die es geht, ist eine ueber ZWEI Staende: aendert sich die Adresse, wenn sich die
 * Anwendung aendert? Solange sie gleich blieb, fragte ein Browser mit warmem Zwischenspeicher
 * sieben Tage lang nicht nach, und der {@code etag} half nicht — er wird nur ausgewertet, wenn
 * ueberhaupt eine Anfrage rausgeht.
 *
 * <p><b>Was hier ein „Release" ist.</b> {@link RessourcenStand#setze(String)} zwischen den
 * beiden Abrufen. Genau das tut ein Release auch: es setzt {@code plaintext.version} auf einen
 * neuen Wert. Der Test muss die Anwendung dafuer nicht neu starten — und misst damit sogar
 * strenger als die Wirklichkeit, weil nichts anderes sich mit veraendert.
 *
 * <p><b>Die Gegenprobe steckt in derselben Messung.</b> Nimmt man aus beiden Adressen die Marke
 * heraus, sind sie identisch. Das ist der Stand DAVOR, an derselben Seite abgelesen statt
 * behauptet: zwei Releases, eine Adresse.
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
class RessourcenMarkePlaywrightIT {

    private static final Logger LOG = LoggerFactory.getLogger(RessourcenMarkePlaywrightIT.class);

    private static final String BENUTZER = "pw-marke";
    private static final String PASSWORT = "Playwright-2026!";

    /** Der Stand, mit dem der erste Abruf gemacht wird. */
    private static final String STAND_VORHER = "1.713.0";
    /** Der Stand nach dem „Release" — die einzige Aenderung zwischen den beiden Abrufen. */
    private static final String STAND_NACHHER = "1.714.0";

    /** Eine Zeichenkette, die nur in der echten watch.css steht. */
    private static final String INHALTSPROBE = "--w-schrift";

    /** Alle href der Stylesheets einer Seite, absolut aufgeloest. */
    private static final String SKRIPT_STYLESHEETS = """
            () => Array.from(document.querySelectorAll('link[rel="stylesheet"]')).map(l => l.href)
            """;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "ressourcenmarkeplaywrightit");
    }

    @LocalServerPort
    int port;
    @Autowired
    MyUserRepository userRepository;
    @Autowired
    PasswordEncoder passwordEncoder;

    private Playwright playwright;
    private Browser browser;
    private String urspruenglicherStand;

    private String basis() {
        return "http://localhost:" + port;
    }

    private String url(String pfad) {
        return basis() + pfad;
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
        urspruenglicherStand = RessourcenStand.stand();
    }

    @AfterAll
    void abbau() {
        if (urspruenglicherStand != null) {
            RessourcenStand.setze(urspruenglicherStand);
        }
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    @DisplayName("Karte 1311: nach einem Release lautet die Adresse von watch.css anders — vorher nicht")
    void zweiAbrufeMitEinerAenderungDazwischen() {
        try (BrowserContext ctx = angemeldet()) {
            Page p = ctx.newPage();

            RessourcenStand.setze(STAND_VORHER);
            String adresseVorher = watchCss(p);

            // ── das „Release": derselbe Server, neue Version ──────────────────────────────
            RessourcenStand.setze(STAND_NACHHER);
            String adresseNachher = watchCss(p);

            LOG.info("Karte 1311 — Abruf 1 ({}): {}", STAND_VORHER, adresseVorher);
            LOG.info("Karte 1311 — Abruf 2 ({}): {}", STAND_NACHHER, adresseNachher);

            assertTrue(adresseVorher.contains("rev=" + STAND_VORHER),
                    "Abruf 1 traegt die Marke nicht: " + adresseVorher);
            assertTrue(adresseNachher.contains("rev=" + STAND_NACHHER),
                    "Abruf 2 traegt die Marke nicht: " + adresseNachher);
            assertNotEquals(adresseVorher, adresseNachher,
                    "Beide Releases fuehren auf dieselbe Adresse — dann fragt ein Browser mit "
                            + "warmem Zwischenspeicher sieben Tage lang nicht nach, und genau das "
                            + "war Daniels Befund vom 20.09.2026.");

            // ── Gegenprobe: derselbe Vergleich am Stand DAVOR ─────────────────────────────
            String ohneMarkeVorher = ohneMarke(adresseVorher);
            String ohneMarkeNachher = ohneMarke(adresseNachher);
            LOG.info("Karte 1311 — Stand davor, beide Abrufe: {}", ohneMarkeVorher);
            assertEquals(ohneMarkeVorher, ohneMarkeNachher,
                    "Die Gegenprobe greift nicht: ohne Marke muessten beide Adressen gleich sein "
                            + "— sonst belegt der Unterschied oben etwas anderes als die Marke.");

            // Beide Adressen liefern dieselbe Datei — die Marke ist ein Cache-Schluessel, kein
            // Ausliefer-Schalter. Mojarra liest aus der Anfrage nur ln= und ignoriert den Rest;
            // dieser Abruf ist der Beleg dafuer und nicht die Behauptung.
            pruefeAuslieferung(p, adresseVorher, "Abruf 1");
            pruefeAuslieferung(p, adresseNachher, "Abruf 2");
            pruefeAuslieferung(p, ohneMarkeNachher,
                    "die Adresse OHNE Marke (alte Lesezeichen und aeltere Seiten im Umlauf)");
        }
    }

    @Test
    @DisplayName("Die Frist ist wirklich lang — sonst waere der Befund gegenstandslos")
    void dieFristIstDerGrundWarumDieMarkeNoetigIst() {
        try (BrowserContext ctx = angemeldet()) {
            Page p = ctx.newPage();
            String adresse = watchCss(p);
            APIResponse antwort = p.request().get(adresse);
            String cacheControl = antwort.headers().get("cache-control");
            LOG.info("Karte 1311 — cache-control von watch.css: {}", cacheControl);
            assertNotNull(cacheControl, "watch.css kommt ohne cache-control: " + adresse);
            assertTrue(cacheControl.contains("max-age="), cacheControl);
            long maxAge = Long.parseLong(cacheControl.replaceAll(".*max-age=(\\d+).*", "$1"));
            assertTrue(maxAge >= 3600,
                    "max-age steht auf " + maxAge + " s. Bei einer kurzen Frist waere der Befund "
                            + "von Karte 1311 gegenstandslos, und dieser Test pruefte nichts mehr "
                            + "— dann gehoert er angepasst statt stillschweigend gruen zu bleiben.");
        }
    }

    @Test
    @DisplayName("Die Marke haengt an JEDER eigenen Ressource, nicht nur an watch.css")
    void jedeEigeneRessourceTraegtEineMarke() {
        try (BrowserContext ctx = angemeldet()) {
            Page p = ctx.newPage();
            p.navigate(url("/watch/home.html"));
            p.waitForLoadState();
            @SuppressWarnings("unchecked")
            List<String> hrefs = (List<String>) p.evaluate(SKRIPT_STYLESHEETS);
            assertFalse(hrefs.isEmpty(), "Kein einziges Stylesheet in der Seite — dann misst "
                    + "dieser Test nichts.");
            for (String href : hrefs) {
                LOG.info("Karte 1311 — Stylesheet: {}", href);
                assertTrue(href.contains("rev=") || href.contains("v="),
                        "Dieses Stylesheet traegt weder eigene Bibliotheksversion noch Marke und "
                                + "liegt damit bis zu sieben Tage unveraendert im Browser: " + href);
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────────────────

    private String watchCss(Page p) {
        p.navigate(url("/watch/home.html"));
        p.waitForLoadState();
        @SuppressWarnings("unchecked")
        List<String> hrefs = (List<String>) p.evaluate(SKRIPT_STYLESHEETS);
        return hrefs.stream()
                .filter(h -> h.contains("ln=watch"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "watch.css haengt gar nicht in der Seite — gefunden: " + hrefs));
    }

    private void pruefeAuslieferung(Page p, String adresse, String was) {
        APIResponse antwort = p.request().get(adresse);
        assertEquals(200, antwort.status(),
                was + " antwortet mit HTTP " + antwort.status() + ": " + adresse);
        String inhalt = antwort.text();
        assertTrue(inhalt.contains(INHALTSPROBE),
                was + " liefert nicht die watch.css (kein '" + INHALTSPROBE + "' darin): " + adresse);
    }

    private static String ohneMarke(String adresse) {
        return adresse.replaceAll("[?&]rev=[^&]*", "");
    }

    private BrowserContext angemeldet() {
        BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(390, 844).setLocale("de-CH").setTimezoneId("Europe/Zurich"));
        Page p = ctx.newPage();
        p.navigate(url("/login.html"));
        p.waitForSelector("#username");
        p.fill("#username", BENUTZER);
        p.fill("#password", PASSWORT);
        p.locator("#password").press("Enter");
        p.waitForLoadState();
        assertFalse(p.url().contains("login"), "Login als " + BENUTZER + " schlug fehl: " + p.url());
        p.close();
        return ctx;
    }
}

/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.integration;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISettingsService;
import ch.plaintext.settings.SettingsKeys;
import ch.plaintext.watch.web.WatchStartController;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
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
 * Card 1280: the <b>positive</b> way of the personal phone link, in a real browser.
 *
 * <p>Card 1305 added the two cases about what the confinement <em>feels</em> like: the refusal
 * explains itself instead of showing a whitelabel page, and there is a way out of the session.
 * They live here and not in a class of their own — the statement "the page is still withheld"
 * and the statement "and it says so" are one measurement, and splitting them would let the
 * pleasant half stay green while the sharp half quietly disappeared.</p>
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>Card 1257 built the link and measured everything that must <em>not</em> work: no token 403,
 * invented token 403 (word for word the same, so the answer is no oracle), signed out on
 * {@code /watch/home.html} 302 to the login, an invented {@code /nosec/} address 404 as the
 * positive control, rate limit {@code 403x17 -> 429x9}. What was never measured is the way that
 * <em>has</em> to work: press the button, open the link on a device that has never signed in,
 * see the watch. Nobody could do it by hand — the button is a PrimeFaces Ajax element and the
 * agent is not allowed to type a password into a browser. Daniel therefore chose an integration
 * test on 19.09.2026: the proof stays, and it runs in CI.</p>
 *
 * <h2>Why HTTP 200 is not the statement</h2>
 *
 * <p>Twice in this house a page answered 200 and was empty (cards 1243, 1248): a
 * {@code preRenderView} listener in the wrong place, and a bean that never ran. A test that
 * checks the status code would have been green both times. {@link #uhrIstDa} therefore asks for
 * things that only exist when the session really carries the owner: the heading comes from
 * {@code WatchFrameBean.getTitel()} and reads <em>Watch</em> instead of <em>Übersicht</em> as
 * soon as the registry finds no page for this user, and the position indicator
 * ({@code getPosition()}) is empty in exactly the same case. Both are computed from the user's
 * own page selection, which is read from the database with the identity out of the token
 * session.</p>
 *
 * <h2>What makes it a proof and not a page load</h2>
 *
 * <p>Every positive statement has its counter-check in the same class, because a green run
 * without one only says that the page answers <em>somebody</em>:</p>
 * <ul>
 *   <li>{@link #ohneTokenZeigtDieselbeAdresseDasLogin()} — the fresh browser context really is
 *       signed out. Without this the success above could come from a session that was lying
 *       around, not from the link.</li>
 *   <li>{@link #abgeschalteterLinkOeffnetDieUhrNichtMehr()} and
 *       {@link #neuGenerierenMachtDenAltenLinkSofortWertlos()} — the <b>same</b> link that
 *       worked a moment ago is refused after the revocation. That is the assertion that goes red
 *       when somebody takes the revocation out.</li>
 *   <li>{@link #dieTokenSitzungKommtAusDerUhrNichtHeraus()} — and afterwards the watch still
 *       works, so the confinement locks the way out and not the session.</li>
 *   <li>{@link #nichtWatchSeiteErklaertSichStattWhitelabel()} (card 1305) — the refused page
 *       explains itself instead of showing the whitelabel page, <b>and</b> the requested page
 *       is still not delivered. Without the second half this one would be green with the
 *       confinement gone altogether — which is exactly what card 1280 found.</li>
 *   <li>{@link #abmeldenBeendetDieSitzungOhneDenLinkZuWiderrufen()} (card 1305) — signing out
 *       ends the session, the <b>same</b> link carries afterwards (signing out is not
 *       revoking), and a revoked link is still refused.</li>
 *   <li>{@link #offeneSitzungStirbtMitDemWiderruf()} — an <b>already open</b> session ends on
 *       the next tap, not at the session timeout.</li>
 *   <li>{@link #abgeschalteteSeiteIstUeberDenLinkNichtErreichbar()} — switched off it is gone,
 *       switched on again it is back. Without the second half the test would also be green if
 *       the watch were simply always empty.</li>
 *   <li>{@link #handyLinkOeffnetDieZuletztOffeneSeite()} and
 *       {@link #homeBildschirmAdresseOeffnetDieZuletztOffeneSeite()} (card 1289) — the
 *       <b>same</b> link and the <b>same</b> saved address land on two different pages,
 *       depending only on which one was open last. Without the first half a start that always
 *       went to the second page would be green as well.</li>
 * </ul>
 *
 * <h2>What it found on its first run (20.09.2026)</h2>
 *
 * <p>The last two of those were <b>red</b>, and not because of the test: every page of this
 * house is addressed as {@code .html}, and {@code UrlRewriteConfig.HtmlToXhtmlRewriteFilter}
 * reaches the view with a {@code RequestDispatcher.forward()} long before
 * {@code WatchTokenSitzungFilter} is in the chain. The filter was registered for
 * {@code DispatcherType.REQUEST} only and therefore ran on no page at all — a token session
 * reached {@code /index.html} with HTTP 200, {@code /watch-einstellungen.html} with it, and the
 * per-request revocation check never fired on the watch pages either. Every unit test of that
 * filter was green throughout, because a unit test hands the filter a path and has no dispatch
 * type. Fixed in {@code WatchTokenFilterConfig} by registering {@code FORWARD} as well; the
 * numbers before and after are in card 1280.</p>
 *
 * <h2>Where it runs, and why here</h2>
 *
 * <p>Next to {@link SelfServicePlaywrightIT} and {@link RootPagesPlaywrightIT}, and in the same
 * {@code -Dit.test=} list of {@code .woodpecker/playwright.yml}. The whole flow lives in
 * {@code plaintext-root-watch}, and this is the only module that boots it with a browser in
 * front of it. Putting it into plaintext-app instead would test the link against a released root
 * jar — the counter-check "take the revocation out, the test goes red" would then need a root
 * release to be measurable at all.</p>
 *
 * <p>Traps of the self-hosted runners (memory {@code playwright_smoke_self_hosted_fallen}): the
 * step runs as root, so {@code initdb} refuses and {@link EmbeddedPg} takes
 * {@code SPRING_DATASOURCE_URL} of the service container; the class skips itself without a
 * Chromium, which is why {@code playwright.yml} checks {@code PLAYWRIGHT_BROWSERS_PATH} before
 * the run instead of trusting a green result.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.docker.compose.enabled=false",
                // Der strenge Eimer von /nosec/watch steht auf 20/min/IP (Karte 1257). Diese
                // Klasse steigt rund ein Dutzend Mal ueber diesen Pfad ein, alles von 127.0.0.1
                // und in weniger als einer Minute. Bliebe die Vorgabe stehen, antwortete
                // irgendwann 429 statt 403 — und dann pruefte die Gegenprobe unten nicht mehr den
                // Widerruf, sondern die Drossel. Die Drossel selbst ist nicht Gegenstand dieser
                // Klasse: sie ist auf PROD gemessen (403x17 -> 429x9, Karte 1257). Damit ein
                // Rutschen trotzdem auffaellt, verlangen die Negativproben ausdruecklich 403 und
                // nennen einen abweichenden Status in der Meldung.
                "plaintext.rate-limit.nosec-token.max-requests=500"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WatchHandyLinkPlaywrightIT {

    private static final String BENUTZER = "pw-watch";
    private static final String PASSWORT = "Playwright-2026!";

    /** Was der Einstieg bei jeder Zurueckweisung antwortet — wortgleich, absichtlich kein Orakel. */
    private static final String ABWEISUNG = "Dieser Link gilt nicht mehr.";

    /** Titel der einzigen Seite, die in root im Umlauf ist ({@code WatchHomePage.title()}). */
    private static final String UHR_TITEL = "Übersicht";

    /**
     * Titel der Seite ausserhalb des Umlaufs ({@code WatchTestPage.title()}). In root ist sie
     * die einzige zweite Seite — und damit die einzige, an der sich „die zuletzt offene Seite"
     * ueberhaupt von „immer dieselbe" unterscheiden laesst (Karte 1289).
     */
    private static final String ZWEITE_TITEL = "Seiten";

    /** Adresse ebendieser zweiten Seite. */
    private static final String ZWEITE_ADRESSE = "/watch/elemente.html";

    /** Adresse der ersten Seite im Umlauf — der Rueckfall, wenn nichts gemerkt ist. */
    private static final String ERSTE_ADRESSE = "/watch/home.html";

    /** Fehlerbild im HTML — dieselbe enge Fassung wie in {@code AllPagesSmokePlaywrightIT}. */
    private static final Pattern AUSNAHME_IM_HTML = Pattern.compile(
            "Whitelabel Error Page|Internal Server Error"
                    + "|jakarta\\.servlet\\.[A-Za-z.]*Exception"
                    + "|jakarta\\.faces\\.[A-Za-z.]*Exception"
                    + "|java\\.lang\\.[A-Za-z]+(Exception|Error)"
                    + "|Error Rendering View");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "watchhandylinkplaywrightit");
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

    private String url(String pfad) {
        return basis() + pfad;
    }

    private String basis() {
        return "http://localhost:" + port;
    }

    @BeforeAll
    void aufbau() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "Chromium not installed: " + e.getMessage());
        }

        // Rollen OHNE "ROLE_"-Praefix: MyUserDetailsService setzt ihn selbst davor (siehe
        // RootPagesPlaywrightIT). USER reicht fuer den Menuepunkt der Watch-Einstellungen
        // (WatchMenu: USER, ADMIN, ROOT); ADMIN kommt dazu, damit die Sitzung derjenigen des
        // echten Benutzers entspricht und die Pfad-Einsperrung etwas zu sperren hat.
        if (userRepository.findByUsername(BENUTZER) == null) {
            MyUserEntity u = new MyUserEntity();
            u.setUsername(BENUTZER);
            u.setPassword(passwordEncoder.encode(PASSWORT));
            u.addRole("USER");
            u.addRole("ADMIN");
            u.setMandat("default");
            userRepository.save(u);
        }

        // Karte 1277: ohne app.ownhost gibt WatchHandyLinkService einen Link OHNE Schema und
        // Host aus — auf einem Telefon kein Link, sondern ein Textschnipsel. Der Wert wird hier
        // gesetzt, weil der Test die AUSGEGEBENE Adresse aufruft und nicht eine selbst
        // zusammengesetzte: nur so belegt der Lauf, dass das, was auf der Maske steht, auch
        // traegt. Der Port ist zufaellig, deshalb ueber die Einstellung und nicht ueber eine
        // Property (@DynamicPropertySource laeuft, bevor der Port feststeht).
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

    // ================================================================= der Positivweg

    @Test
    @DisplayName("gueltiger Handy-Link oeffnet die Uhr in einem Browser ohne Sitzung")
    void gueltigerLinkOeffnetDieUhrOhneAnmeldung() {
        String link;
        try (BrowserContext admin = angemeldeterKontext()) {
            Page maske = einstellungen(admin);
            link = erzeuge(maske);
        }

        // Ein FRISCHER Kontext: eigener Cookie-Topf, keine Sitzung, kein Login. Das ist das
        // Telefon, dem der Link geschickt wurde.
        try (BrowserContext telefon = frischerKontext()) {
            Page p = telefon.newPage();
            Response antwort = p.navigate(link);
            p.waitForLoadState();

            assertNotNull(antwort, "keine Antwort auf " + redigiert(link));
            assertEquals(200, antwort.status(),
                    "Der Handy-Link antwortete mit HTTP " + antwort.status() + " statt 200 — "
                            + "Adresse (ohne Token): " + redigiert(link));
            uhrIstDa(p, "nach dem Einstieg ueber den frisch erzeugten Link");

            // Der Token darf nach dem Einstieg nirgends mehr stehen: die Weiterleitung ERSETZT
            // den Verlaufseintrag, deshalb liegt die Adresse mit Token nicht einmal hinter dem
            // Zurueck-Knopf (Karte 1257).
            assertFalse(p.url().contains("t="),
                    "Der Token steht nach dem Einstieg noch in der Adresse: " + p.url());
        }
    }

    /**
     * Die Kontrolle, ohne die der Test oben nichts belegt: ist der frische Kontext wirklich
     * abgemeldet? Waere irgendwo noch eine Sitzung, kaeme die Uhr auch ohne Token — und der
     * gruene Lauf oben sagte nur, dass die Seite <em>irgendwem</em> antwortet.
     */
    @Test
    @DisplayName("Gegenprobe: dieselbe Adresse ohne Token zeigt das Login, nicht die Uhr")
    void ohneTokenZeigtDieselbeAdresseDasLogin() {
        try (BrowserContext telefon = frischerKontext()) {
            Page p = telefon.newPage();
            p.navigate(url("/watch/home.html"));
            p.waitForLoadState();

            assertTrue(p.url().contains("login"),
                    "Ein Browser ohne Sitzung kam ohne Token bis " + p.url()
                            + " — dann belegt der Positivtest nicht den Link, sondern eine "
                            + "offene Tuer.");
            assertEquals(0, p.locator(".w-wrap").count(), "Uhr ohne Anmeldung sichtbar");
        }
    }

    // ================================================================= Widerruf

    @Test
    @DisplayName("Deaktivieren macht denselben Link sofort wertlos")
    void abgeschalteterLinkOeffnetDieUhrNichtMehr() {
        String link;
        try (BrowserContext admin = angemeldeterKontext()) {
            Page maske = einstellungen(admin);
            link = erzeuge(maske);

            // ERST belegen, dass der Link traegt. Ohne diesen Schritt koennte die 403 unten auch
            // von einem Link kommen, der nie funktioniert hat.
            try (BrowserContext vorher = frischerKontext()) {
                Page p = vorher.newPage();
                p.navigate(link);
                p.waitForLoadState();
                uhrIstDa(p, "vor dem Deaktivieren");
            }

            schalteAb(maske);
        }

        try (BrowserContext nachher = frischerKontext()) {
            abgewiesen(nachher, link, "derselbe Link nach dem Deaktivieren");
        }
    }

    @Test
    @DisplayName("Neu generieren toetet den alten Link und stellt einen tragenden neuen aus")
    void neuGenerierenMachtDenAltenLinkSofortWertlos() {
        String alt;
        String neu;
        try (BrowserContext admin = angemeldeterKontext()) {
            Page maske = einstellungen(admin);
            alt = erzeuge(maske);
            neu = erzeuge(maske);
        }
        assertFalse(alt.equals(neu), "Zweimal derselbe Link — es wurde kein neuer ausgestellt");

        try (BrowserContext mitAltem = frischerKontext()) {
            abgewiesen(mitAltem, alt, "der alte Link nach dem Neugenerieren");
        }
        // Die andere Haelfte, sonst waere der Test auch gruen, wenn "neu generieren" beide
        // Links widerriefe.
        try (BrowserContext mitNeuem = frischerKontext()) {
            Page p = mitNeuem.newPage();
            p.navigate(neu);
            p.waitForLoadState();
            uhrIstDa(p, "mit dem neu generierten Link");
        }
    }

    // ================================================================= Einsperrung

    @Test
    @DisplayName("die Token-Sitzung kommt aus der Uhr nicht heraus, bleibt in ihr aber gueltig")
    void dieTokenSitzungKommtAusDerUhrNichtHeraus() {
        String link;
        try (BrowserContext admin = angemeldeterKontext()) {
            link = erzeuge(einstellungen(admin));
        }

        try (BrowserContext telefon = frischerKontext()) {
            Page p = telefon.newPage();
            p.navigate(link);
            p.waitForLoadState();
            uhrIstDa(p, "vor der Probe auf die Einsperrung");

            // Der Besitzer ist ADMIN — ohne die Pfad-Einsperrung waere dieser Link seine ganze
            // Sitzung. Beide Adressen stehen NICHT auf der Erlaubnisliste von
            // WatchTokenSitzungFilter, watch-einstellungen.html ausdruecklich nicht: ein Link,
            // der sich selbst neu ausstellen koennte, waere seine eigene Verwaltung.
            zugesperrt(p, "/index.html");
            zugesperrt(p, "/watch-einstellungen.html");

            // Und danach traegt die Sitzung weiter: die Einsperrung sperrt den Weg hinaus, nicht
            // die Sitzung. Ohne diese Zeile waere der Test auch gruen, wenn jede Anfrage 403
            // bekaeme.
            p.navigate(url("/watch/home.html"));
            p.waitForLoadState();
            uhrIstDa(p, "nach den beiden abgewiesenen Ausfluegen");
        }
    }

    // ================================================================= Karte 1305: Antwort und Ausgang

    /**
     * Die Einsperrung aus dem Test darueber, von der anderen Seite gesehen: was steht auf dem
     * Telefon?
     *
     * <p>Bis zum 20.09.2026 stand dort Spring Boots Whitelabel-Seite — drei Zeilen Englisch,
     * keine Erklaerung, kein Weg zurueck. Daniel hat genau das auf seinem eigenen Telefon
     * gesehen und fuer einen kaputten Deploy gehalten. Der Test verlangt beide Haelften: die
     * Erklaerung <b>und</b> dass die angeforderte Seite weiterhin nicht kommt. Ohne die zweite
     * Haelfte waere er auch gruen, wenn die Schranke ganz gefallen waere — und genau das war
     * heute frueh der Befund.</p>
     */
    @Test
    @DisplayName("eine Nicht-Watch-Seite erklaert sich, statt die Whitelabel-Seite zu zeigen")
    void nichtWatchSeiteErklaertSichStattWhitelabel() {
        String link;
        try (BrowserContext admin = angemeldeterKontext()) {
            link = erzeuge(einstellungen(admin));
        }

        try (BrowserContext telefon = frischerKontext()) {
            Page p = telefon.newPage();
            p.navigate(link);
            p.waitForLoadState();
            uhrIstDa(p, "vor der Probe auf die Sperrseite");

            Response antwort = p.navigate(url("/index.html"));
            p.waitForLoadState();

            // ---- Haelfte 1: die Schranke steht unveraendert.
            assertNotNull(antwort, "keine Antwort auf /index.html");
            assertEquals(403, antwort.status(),
                    "Eine Token-Sitzung kam auf /index.html mit HTTP " + antwort.status()
                            + " durch. Die Erklaerung darf die Schranke nicht ersetzen.");
            assertEquals(0, p.locator(".dashboard-grid").count(),
                    "Die Kacheln des Dashboards stehen auf der Seite — dann ist /index.html "
                            + "trotz 403 ausgeliefert worden: " + auszug(p));
            assertFalse(p.content().contains("plaintext-layout"),
                    "Die Seite laedt die Layout-Ressourcen der normalen Masken, ist also das "
                            + "Dashboard und nicht die Sperrseite: " + auszug(p));

            // ---- Haelfte 2: und sie sagt, was los ist.
            assertFalse(p.content().contains("Whitelabel Error Page"),
                    "Immer noch die Whitelabel-Seite: " + auszug(p));
            assertEquals(1, p.locator("#wt-sperre").count(),
                    "Die Sperrseite fehlt — der Container antwortet wieder selbst: " + auszug(p));
            assertEquals(1, p.locator("#wt-zur-uhr").count(),
                    "Kein Weg zurueck zur Uhr: " + auszug(p));
            assertEquals(1, p.locator("#wt-abmelden").count(),
                    "Kein Ausgang aus der Sitzung: " + auszug(p));

            // ---- Und der angebotene Weg zurueck traegt wirklich.
            p.click("#wt-zur-uhr");
            p.waitForLoadState();
            uhrIstDa(p, "nach dem Klick auf 'Zurueck zur Uhr'");
        }
    }

    /**
     * Der Ausgang: abmelden beendet die Sitzung — und <b>nur</b> sie.
     *
     * <p>Der Filterkommentar begruendet die Einsperrung damit, dass ein Link sich nicht selbst
     * verwalten soll. Abmelden ist kein Verwalten: es widerruft nichts und stellt nichts aus,
     * es beendet eine Sitzung auf einem Geraet. Dieser Test misst genau diesen Unterschied und
     * braucht dafuer drei Haelften — ohne die zweite waere er auch gruen, wenn Abmelden den
     * Link mit abraeumte, ohne die dritte auch dann, wenn Widerrufen gar nicht mehr wirkte.</p>
     */
    @Test
    @DisplayName("abmelden beendet die Sitzung, der Link traegt weiter — widerrufen nicht")
    void abmeldenBeendetDieSitzungOhneDenLinkZuWiderrufen() {
        String link;
        try (BrowserContext admin = angemeldeterKontext()) {
            link = erzeuge(einstellungen(admin));
        }

        try (BrowserContext telefon = frischerKontext()) {
            Page p = telefon.newPage();
            p.navigate(link);
            p.waitForLoadState();
            uhrIstDa(p, "vor dem Abmelden");

            // Der Knopf steht auf der Sperrseite — dort, wo man ihn braucht.
            p.navigate(url("/index.html"));
            p.waitForLoadState();
            assertEquals(1, p.locator("#wt-abmelden").count(),
                    "Ohne Abmeldeknopf kommt man aus dieser Sitzung nur heraus, indem man die "
                            + "Website-Daten im Browser loescht: " + auszug(p));
            p.click("#wt-abmelden button");
            p.waitForLoadState();
            assertTrue(p.url().contains("login"),
                    "Abmelden fuehrte nicht zur Anmeldung, sondern auf " + p.url()
                            + ". Haeufigste Ursache: das CSRF-Feld auf der Sperrseite fehlt — "
                            + "/logout ist ein gepruefter POST.");

            // ---- Haelfte 1: die Sitzung ist wirklich weg, nicht nur die Anzeige.
            p.navigate(url("/watch/home.html"));
            p.waitForLoadState();
            assertTrue(p.url().contains("login"),
                    "Nach dem Abmelden traegt die Uhr weiter (" + p.url() + ") — dann hat der "
                            + "Knopf nur die Seite gewechselt und nichts beendet.");
            assertEquals(0, p.locator(".w-wrap").count(),
                    "Die Uhr ist nach dem Abmelden noch da: " + auszug(p));

            // ---- Haelfte 2: DERSELBE Link traegt weiterhin. Abmelden ist kein Widerruf.
            p.navigate(link);
            p.waitForLoadState();
            uhrIstDa(p, "mit dem alten Link nach dem Abmelden");
        }

        // ---- Haelfte 3: was den Link wirklich beendet, wirkt weiterhin.
        try (BrowserContext admin = angemeldeterKontext()) {
            schalteAb(einstellungen(admin));
        }
        try (BrowserContext telefon = frischerKontext()) {
            abgewiesen(telefon, link, "nach dem Widerruf (Gegenprobe zum Abmelden)");
        }
    }

    // ================================================================= Negativproben am Einstieg

    @Test
    @DisplayName("ohne Token und mit erfundenem Token 403 — und der Endpunkt gibt es wirklich")
    void ohneUndMitErfundenemToken403() {
        try (BrowserContext fremd = frischerKontext()) {
            Page p = fremd.newPage();

            Response ohne = p.navigate(url("/nosec/watch"));
            assertNotNull(ohne, "keine Antwort auf /nosec/watch");
            assertEquals(403, ohne.status(), "ohne Token kam HTTP " + ohne.status() + " statt 403");
            assertTrue(p.content().contains(ABWEISUNG), "andere Abweisung als erwartet");

            Response erfunden = p.navigate(url("/nosec/watch?t=nicht.ein.token"));
            assertNotNull(erfunden, "keine Antwort auf den erfundenen Token");
            assertEquals(403, erfunden.status(),
                    "erfundener Token kam mit HTTP " + erfunden.status() + " statt 403");
            assertTrue(p.content().contains(ABWEISUNG),
                    "Die Abweisung unterscheidet sich von der ohne Token — das waere ein Orakel, "
                            + "das dem Rater sagt, wie nah er ist.");

            // Positivkontrolle: die 403 oben kommt von DIESEM Endpunkt und nicht von einer
            // Pauschalregel ueber /nosec/. Eine erfundene Adresse daneben wird ANDERS
            // behandelt — im Browser fuehrt sie ueber den 404 und PlaintextErrorViewResolver
            // (Karte 406) auf die Startseite und von dort aufs Login. Auf PROD wurde hier mit
            // curl eine nackte 404 gemessen (Karte 1257); der Unterschied ist der
            // Accept-Kopf, nicht das Verhalten des Endpunkts: der Resolver greift nur in den
            // HTML-Zweig ein.
            p.navigate(url("/nosec/watchgibtsnicht"));
            p.waitForLoadState();
            assertFalse(p.url().contains("watchgibtsnicht"),
                    "Eine erfundene /nosec/-Adresse blieb stehen statt umgeleitet zu werden: "
                            + p.url());
            assertFalse(p.content().contains(ABWEISUNG),
                    "Auch die erfundene Adresse antwortet mit dem Satz des Handy-Links — dann "
                            + "sagt die 403 oben nichts ueber den Endpunkt aus, sondern nur, "
                            + "dass irgendeine Pauschalregel ueber /nosec/ liegt.");
        }
    }

    // ================================================================= Widerruf bei OFFENER Sitzung

    /**
     * Die schaerfste Zusicherung der ganzen Karte: „Widerruf wirkt sofort" gilt nur, wenn eine
     * <b>bereits offene</b> Sitzung beim naechsten Tippen endet — nicht erst beim Timeout.
     *
     * <p>Genau hier lag der Befund vom 20.09.2026: der Handy-Link wird als {@code .html}
     * geoeffnet, und {@code HtmlToXhtmlRewriteFilter} leitet solche Adressen per
     * {@code forward()} weiter, bevor {@code WatchTokenSitzungFilter} an der Reihe ist. Der
     * Filter war nur fuer {@code DispatcherType.REQUEST} angemeldet und lief auf dem
     * weitergeleiteten Durchgang nicht mit — die Pruefung je Anfrage fand auf keiner Seite
     * statt. Dieser Test war rot, bis {@code WatchTokenFilterConfig} auch {@code FORWARD}
     * anmeldet.</p>
     */
    @Test
    @DisplayName("eine schon offene Sitzung endet beim naechsten Tippen, nicht erst beim Timeout")
    void offeneSitzungStirbtMitDemWiderruf() {
        try (BrowserContext admin = angemeldeterKontext();
             BrowserContext telefon = frischerKontext()) {
            Page maske = einstellungen(admin);
            String link = erzeuge(maske);

            Page p = telefon.newPage();
            p.navigate(link);
            p.waitForLoadState();
            uhrIstDa(p, "die Sitzung, die gleich widerrufen wird");

            schalteAb(maske);

            // Dieselbe offene Sitzung, dieselbe Adresse — nur ein Tippen spaeter.
            Response antwort = p.navigate(url("/watch/home.html"));
            p.waitForLoadState();
            assertNotNull(antwort, "keine Antwort auf die Uhr nach dem Widerruf");
            assertEquals(403, antwort.status(),
                    "Die offene Sitzung lebt nach dem Widerruf weiter (HTTP " + antwort.status()
                            + "). Dann stoppt 'Deaktivieren' das Telefon erst beim Timeout — und "
                            + "die Pruefung je Anfrage laeuft auf den Seiten gar nicht.");
            assertEquals(0, p.locator(".w-wrap").count(),
                    "Die Uhr ist nach dem Widerruf noch da: " + auszug(p));
        }
    }

    // ================================================================= Seitenauswahl

    @Test
    @DisplayName("eine abgeschaltete Seite ist ueber den Link nicht erreichbar, auch nicht direkt")
    void abgeschalteteSeiteIstUeberDenLinkNichtErreichbar() {
        String link;
        try (BrowserContext admin = angemeldeterKontext()) {
            link = erzeuge(einstellungen(admin));
        }

        try (BrowserContext telefon = frischerKontext()) {
            Page p = telefon.newPage();
            p.navigate(link);
            p.waitForLoadState();
            uhrIstDa(p, "vor dem Abschalten der Seite");

            try {
                schalteSeite(p, UHR_TITEL, false);

                // Direktaufruf der View-Id: frueher antwortete /watch/elemente.html mit 200 und
                // der vollen Seite, obwohl der Benutzer sie abgeschaltet hatte (Karte 1257).
                p.navigate(url("/watch/home.html"));
                p.waitForLoadState();
                assertFalse(kopfzeile(p).equals(UHR_TITEL),
                        "Die abgeschaltete Seite zeigt sich weiterhin als '" + UHR_TITEL
                                + "' — der Schalter verspricht dann etwas, das er nicht haelt.");
                assertTrue(position(p).isEmpty(),
                        "Die abgeschaltete Seite zaehlt noch im Umlauf mit: '" + position(p) + "'");
            } finally {
                schalteSeite(p, UHR_TITEL, true);
            }

            // Die positive Haelfte: eingeschaltet ist dieselbe Adresse wieder die Uhr. Ohne sie
            // waere der Test auch gruen, wenn die Uhr grundsaetzlich leer bliebe.
            p.navigate(url("/watch/home.html"));
            p.waitForLoadState();
            uhrIstDa(p, "nach dem Wiedereinschalten der Seite");
        }
    }

    // ================================================================= Start auf der letzten Seite

    /**
     * Karte 1289: die Uhr startet auf der Seite, die zuletzt offen war.
     *
     * <h2>Warum der Beleg aus zwei Haelften bestehen muss</h2>
     *
     * <p>Ein Test, der nur „nach dem Oeffnen von {@code elemente} landet der Link auf
     * {@code elemente}" misst, waere auch dann gruen, wenn der Einstieg schlicht immer dorthin
     * ginge. Deshalb wird <b>derselbe Link</b> zweimal aus einem frischen Browser gestartet,
     * einmal mit gemerkter erster Seite und einmal mit gemerkter zweiter — und er muss beide
     * Male woanders landen. Die erste Haelfte ist zugleich die Gegenprobe zur Lage vor dieser
     * Karte: damals landete er immer auf {@code /watch/home.html}.</p>
     *
     * <p>Gemerkt wird ueber einen echten Seitenaufruf und nicht ueber die Datenbank: genau das
     * tut {@code WatchFrameBean} bei jedem Wechsel, und nur so belegt der Lauf die ganze
     * Strecke vom Tippen bis zum naechsten Start.</p>
     */
    @Test
    @DisplayName("der Handy-Link oeffnet die zuletzt offene Seite, nicht immer die erste")
    void handyLinkOeffnetDieZuletztOffeneSeite() {
        String link;
        try (BrowserContext admin = angemeldeterKontext()) {
            link = erzeuge(einstellungen(admin));
        }

        try {
            // ---- Haelfte 1 (Gegenprobe): gemerkt ist die erste Seite, also kommt sie.
            merkeUeberDenLink(link, ERSTE_ADRESSE);
            try (BrowserContext telefon = frischerKontext()) {
                Page p = telefon.newPage();
                p.navigate(link);
                p.waitForLoadState();
                uhrIstDa(p, "Start mit gemerkter erster Seite");
            }

            // ---- Haelfte 2: eine andere Seite zuletzt offen — und der Start folgt ihr.
            merkeUeberDenLink(link, ZWEITE_ADRESSE);
            try (BrowserContext telefon = frischerKontext()) {
                Page p = telefon.newPage();
                Response antwort = p.navigate(link);
                p.waitForLoadState();

                assertNotNull(antwort, "keine Antwort auf " + redigiert(link));
                assertEquals(200, antwort.status(),
                        "Der Start antwortete mit HTTP " + antwort.status() + " statt 200");
                assertTrue(p.url().endsWith(ZWEITE_ADRESSE),
                        "Der Handy-Link landete auf " + p.url() + " statt auf " + ZWEITE_ADRESSE
                                + ". Zuletzt offen war diese Seite — wird sie beim Start nicht "
                                + "gelesen, ist der gemerkte Zustand nur Schreibarbeit.");
                // Nicht nur die Adresse: eine Seite mit Status 200 kann leer sein (Karten 1243,
                // 1248). Die Kopfzeile kommt aus WatchFrameBean und nennt die Seite, die
                // wirklich gerendert wurde.
                assertEquals(ZWEITE_TITEL, kopfzeile(p),
                        "Die Adresse stimmt, die Seite nicht: die Kopfzeile sagt '"
                                + kopfzeile(p) + "'. Seitenauszug: " + auszug(p));
                assertEquals(1, p.locator(".w-wrap").count(),
                        "Der Rahmen der Uhr fehlt — Seitenauszug: " + auszug(p));
                assertFalse(AUSNAHME_IM_HTML.matcher(p.content()).find(),
                        "Serverfehler im HTML der Uhr: " + auszug(p));
                assertFalse(p.url().contains("t="),
                        "Der Token steht nach dem Einstieg noch in der Adresse: " + p.url());
            }
        } finally {
            // Der gemerkte Zustand ist je Benutzer und ueberlebt den Test. Ohne dieses
            // Aufraeumen startet jeder folgende Test dieser Klasse auf 'elemente', und
            // uhrIstDa() faende dort weder die Adresse noch den Titel, den es erwartet.
            merkeUeberDenLink(link, ERSTE_ADRESSE);
        }
    }

    /**
     * Die andere Haelfte des Auftrags: das Symbol auf dem Home-Bildschirm. Es wird EINMAL
     * gespeichert und danach jahrelang getippt — stuende darin eine konkrete Seite, oeffnete es
     * fuer immer diese eine.
     *
     * <p>Die Adresse wird aus der Maske gelesen und nicht zusammengebaut: nur so belegt der
     * Lauf, dass das, was dort zum Speichern angeboten wird, auch traegt (dieselbe Regel wie
     * beim Handy-Link in {@link #erzeuge(Page)}).</p>
     */
    @Test
    @DisplayName("die Adresse fuer den Home-Bildschirm oeffnet die zuletzt offene Seite")
    void homeBildschirmAdresseOeffnetDieZuletztOffeneSeite() {
        try (BrowserContext admin = angemeldeterKontext()) {
            Page p = einstellungen(admin);
            String adresse = p.locator("#fm\\:adresseLink").getAttribute("href");

            assertNotNull(adresse, "Die Maske zeigt keine Adresse zum Speichern an");
            assertTrue(adresse.startsWith(basis()),
                    "Die angebotene Adresse ist nicht absolut und auf einem Telefon kein Link: "
                            + adresse);
            assertTrue(adresse.endsWith(WatchStartController.PFAD),
                    "Die Maske bietet " + adresse + " zum Speichern an. Nennt sie eine konkrete "
                            + "Seite, friert jedes gespeicherte Symbol genau diese ein.");

            try {
                // ---- Gegenprobe: gemerkt ist die erste Seite.
                oeffne(p, ERSTE_ADRESSE);
                p.navigate(adresse);
                p.waitForLoadState();
                assertTrue(p.url().endsWith(ERSTE_ADRESSE),
                        "Die gespeicherte Adresse landete auf " + p.url() + " statt auf "
                                + ERSTE_ADRESSE);
                assertEquals(UHR_TITEL, kopfzeile(p), "Seitenauszug: " + auszug(p));

                // ---- Und jetzt dieselbe Adresse mit einer anderen zuletzt offenen Seite.
                oeffne(p, ZWEITE_ADRESSE);
                p.navigate(adresse);
                p.waitForLoadState();
                assertTrue(p.url().endsWith(ZWEITE_ADRESSE),
                        "Dieselbe gespeicherte Adresse landete auf " + p.url() + " statt auf "
                                + ZWEITE_ADRESSE + " — dann nuetzt das Merken beim Start nichts.");
                assertEquals(ZWEITE_TITEL, kopfzeile(p),
                        "Die Adresse stimmt, die Seite nicht: '" + kopfzeile(p)
                                + "'. Seitenauszug: " + auszug(p));
                assertEquals(1, p.locator(".w-wrap").count(),
                        "Der Rahmen der Uhr fehlt — Seitenauszug: " + auszug(p));
                assertFalse(AUSNAHME_IM_HTML.matcher(p.content()).find(),
                        "Serverfehler im HTML der Uhr: " + auszug(p));
            } finally {
                oeffne(p, ERSTE_ADRESSE);
            }
        }
    }

    // ================================================================= Werkzeug

    private BrowserContext frischerKontext() {
        return browser.newContext(new Browser.NewContextOptions()
                // Telefonformat: die Uhr ist fuer kleine Anzeigen gebaut, und ein 1920er Fenster
                // verdeckt Umbruchfehler, die auf dem Geraet auffallen.
                .setViewportSize(390, 844)
                .setLocale("de-CH")
                .setTimezoneId("Europe/Zurich"));
    }

    private BrowserContext angemeldeterKontext() {
        BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 900).setLocale("de-CH").setTimezoneId("Europe/Zurich"));
        Page p = ctx.newPage();
        p.navigate(url("/login.html"));
        p.waitForSelector("#username");
        p.fill("#username", BENUTZER);
        p.fill("#password", PASSWORT);
        p.locator("#password").press("Enter");
        p.waitForLoadState();
        assertFalse(p.url().contains("login"),
                "Login als " + BENUTZER + " schlug fehl: " + p.url());
        return ctx;
    }

    /** Die Watch-Einstellungen des angemeldeten Benutzers, offen und geladen. */
    private Page einstellungen(BrowserContext ctx) {
        Page p = ctx.pages().get(0);
        p.navigate(url("/watch-einstellungen.html"));
        p.waitForLoadState();
        assertTrue(p.url().contains("watch-einstellungen"), "umgeleitet nach " + p.url());
        assertTrue(p.locator("#fm\\:btnErzeugen").count() > 0,
                "Der Knopf 'Handy-Link erstellen' fehlt. Fehlt das Token-Modul, sagt die Maske "
                        + "das ausdruecklich — dann waere dieser Test ohne Aussage.");
        return p;
    }

    /**
     * Drueckt „Handy-Link erstellen" und liest den Link, der genau einmal angezeigt wird.
     *
     * <p>Der Wert wird nirgends ausgegeben — weder ganz noch gekuerzt. Jede Meldung dieser Klasse
     * geht durch {@link #redigiert(String)}.</p>
     */
    private String erzeuge(Page maske) {
        Locator feld = maske.locator("#fm\\:linkFeld");
        // Beim zweiten Druck ist das Feld SCHON da und traegt noch den alten Wert. Ein
        // waitFor(VISIBLE) kaeme dann sofort zurueck und liesse den Test den Vorgaenger lesen —
        // die Probe "der alte Link ist tot" liefe gegen den alten Link selbst und waere
        // sinnlos gruen. Deshalb wird auf die AENDERUNG gewartet, nicht auf das Feld.
        String vorher = feld.count() > 0 ? feld.inputValue() : "";
        maske.click("#fm\\:btnErzeugen");

        String link = vorher;
        // Die Growl-Meldung verschwindet nach 8 s von selbst (life="8000"). Wer sie erst nach
        // dem Wartefenster liest, liest nichts — und die Fehlermeldung nennt dann keinen Grund.
        String meldung = "";
        long ende = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < ende) {
            if (feld.count() > 0) {
                link = feld.inputValue();
                if (!link.equals(vorher)) {
                    break;
                }
            }
            String jetzt = meldungen(maske);
            if (!jetzt.isBlank()) {
                meldung = jetzt;
            }
            maske.waitForTimeout(200);
        }
        assertFalse(link.equals(vorher),
                "Nach 30 s zeigt die Maske keinen neuen Handy-Link. Wurde der Ajax-Postback "
                        + "verworfen? Meldung der Maske: '" + meldung + "'");

        assertNotNull(link, "Das Linkfeld ist leer");
        // Karte 1277: eine Adresse ohne Schema und Host ist auf einem Telefon kein Link. Der
        // Test ruft genau diese Zeichenkette auf — waere sie relativ, pruefte er eine Adresse,
        // die er sich selbst zusammengebaut haette.
        assertTrue(link.startsWith(basis() + "/nosec/watch?t="),
                "Der ausgegebene Link ist keine vollstaendige Adresse: " + redigiert(link));
        assertTrue(link.length() > (basis() + "/nosec/watch?t=").length() + 20,
                "Im Link steht kein Token: " + redigiert(link));
        return link;
    }

    /** Drueckt „Deaktivieren" und wartet, bis die Maske den Knopf nicht mehr anbietet. */
    private void schalteAb(Page maske) {
        maske.locator("#fm\\:btnAbschalten").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30_000));
        maske.click("#fm\\:btnAbschalten");
        // Der Knopf haengt an handyLinkAktiv und verschwindet mit dem Ajax-Update von fsHandy.
        // Darauf zu warten ist die verlaessliche Quittung, dass der Postback durch ist.
        maske.locator("#fm\\:btnAbschalten").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED).setTimeout(30_000));
    }

    /**
     * Schaltet eine Seite auf der Uebersicht der Uhr um — dieselbe Auswahl, die auch auf der
     * Einstellungsmaske steht (Karte 1260: ein Mechanismus, zwei Bedienstellen).
     */
    private void schalteSeite(Page p, String titel, boolean an) {
        p.navigate(url("/watch/elemente.html"));
        p.waitForLoadState();
        Locator knopf = schalterKnopf(p, titel);
        if ("an".equals(knopf.inputValue()) == an) {
            return;
        }
        knopf.click();
        p.waitForLoadState();

        // Den neuen Zustand aus einem FRISCHEN Aufruf lesen und nicht aus der Antwort des
        // Postbacks: nur so belegt die Zusicherung, dass der Schalter in der Datenbank steht
        // und nicht bloss in der gerade gerenderten Ansicht.
        p.navigate(url("/watch/elemente.html"));
        p.waitForLoadState();
        assertEquals(an ? "an" : "aus", schalterKnopf(p, titel).inputValue(),
                "Der Schalter fuer '" + titel + "' hat nicht umgeschaltet");
    }

    private Locator schalterKnopf(Page p, String titel) {
        Locator zeile = p.locator("#ue .w-row").filter(new Locator.FilterOptions().setHasText(titel));
        assertEquals(1, zeile.count(),
                "Auf der Uebersicht der Uhr steht keine eindeutige Zeile fuer '" + titel + "'");
        return zeile.locator("input[type=submit], button");
    }

    /**
     * Oeffnet eine Watch-Seite ueber den Handy-Link und laesst sie damit als „zuletzt offen"
     * merken — in einem frischen Browser, damit das Merken wirklich in der Datenbank landet und
     * nicht in einer Sitzung, die der naechste Schritt ohnehin weiterbenutzt (Karte 1289).
     */
    private void merkeUeberDenLink(String link, String pfad) {
        try (BrowserContext telefon = frischerKontext()) {
            Page p = telefon.newPage();
            p.navigate(link);
            p.waitForLoadState();
            oeffne(p, pfad);
        }
    }

    /** Ruft eine Watch-Adresse auf und belegt, dass sie wirklich stehen bleibt. */
    private void oeffne(Page p, String pfad) {
        p.navigate(url(pfad));
        p.waitForLoadState();
        assertTrue(p.url().endsWith(pfad),
                "Konnte " + pfad + " nicht oeffnen, gelandet auf " + p.url()
                        + " — dann merkt der folgende Schritt die falsche Seite.");
    }

    /** Die Growl-Meldungen der Maske — fuer Fehlermeldungen, nie fuer eine Zusicherung. */
    private String meldungen(Page maske) {
        Locator growl = maske.locator("#fm\\:messages");
        return growl.count() == 0 ? "(keine)" : growl.innerText().replaceAll("\\s+", " ").trim();
    }

    // ================================================================= Zusicherungen

    /**
     * Die Uhr ist wirklich da — mit Inhalt, nicht nur mit HTTP 200.
     *
     * <p>Kopfzeile und Positionsanzeige stammen aus {@code WatchFrameBean} und werden aus der
     * Seitenauswahl <b>dieses</b> Benutzers gerechnet. Eine Sitzung ohne aufloesbaren Benutzer
     * liefert „Watch" und eine leere Position — genau die leere Seite mit Status 200, die in den
     * Karten 1243 und 1248 durchgerutscht ist.</p>
     */
    private void uhrIstDa(Page p, String wo) {
        assertTrue(p.url().endsWith(ERSTE_ADRESSE),
                "Nicht auf der Uhr gelandet (" + wo + "): " + p.url()
                        + ". Seit Karte 1289 fuehrt der Einstieg auf die ZULETZT OFFENE Seite — "
                        + "steht hier eine andere Watch-Adresse, hat ein Test vorher etwas "
                        + "anderes gemerkt und nicht aufgeraeumt.");
        assertEquals(1, p.locator(".w-wrap").count(),
                "Der Rahmen der Uhr fehlt (" + wo + ") — Seitenauszug: " + auszug(p));
        assertEquals(UHR_TITEL, kopfzeile(p),
                "Die Uhr zeigt nicht ihren Seitentitel (" + wo + "). 'Watch' bedeutet: die "
                        + "Sitzung hat keinen Benutzer, aus dem sich eine Seite ergibt.");
        assertTrue(position(p).matches("\\d+/\\d+"),
                "Die Positionsanzeige ist leer oder unlesbar (" + wo + "): '" + position(p)
                        + "' — sie wird aus der Seitenauswahl dieses Benutzers gerechnet.");
        assertTrue(p.locator(".w-card").count() > 0,
                "Die Uhr hat keine einzige Karte (" + wo + ") — Seitenauszug: " + auszug(p));
        // Bewusst ">= 1" und nicht eine feste Zahl: seit Karte 1276 ist aus ‹ ≡ › EIN
        // Vorwaerts-Knopf geworden (die Uebersicht haengt am langen Druck). Der Test soll
        // belegen, dass die Uhr bedienbar gerendert ist — nicht, wie viele Knoepfe Daniel
        // gerade will. Genau daran ist der erste CI-Lauf dieser Klasse rot geworden: lokal
        // gruen gegen 1.705.0, im CI gegen 1.706.0 mit dem neuen Knopf.
        //
        // Karte 1312: der Knopf heisst nicht mehr .w-btn. Ein h:commandButton rendert ein
        // <input>, und ein <input> kann keine Kinder tragen — der Hinweis "lang druecken"
        // steht deshalb NEBEN dem Eingabefeld, und die sichtbare Knopfflaeche ist .w-aktion
        // mit dem durchsichtigen .w-aktion-flaeche darueber. Gesucht ist die TREFFERFLAECHE,
        // also das Eingabefeld: es allein belegt, dass der Knopf bedienbar ist. Wie er
        // aussieht, misst WatchMusterPlaywrightIT bei 390px.
        assertTrue(p.locator("#nav .w-aktion-flaeche").count() >= 1,
                "Die Navigation der Uhr fehlt (" + wo + ") — im Formular #nav steht kein "
                        + "einziger .w-aktion-flaeche. Seitenauszug: " + auszug(p));
        assertFalse(AUSNAHME_IM_HTML.matcher(p.content()).find(),
                "Serverfehler im HTML der Uhr (" + wo + "): " + auszug(p));
    }

    /** Der Link wird abgewiesen — 403, wortgleich, und keine Spur der Uhr. */
    private void abgewiesen(BrowserContext ctx, String link, String wann) {
        Page p = ctx.newPage();
        Response antwort = p.navigate(link);
        p.waitForLoadState();

        assertNotNull(antwort, "keine Antwort (" + wann + ")");
        assertEquals(403, antwort.status(),
                wann + " antwortete mit HTTP " + antwort.status() + " statt 403. Bei 429 hat die "
                        + "Drossel zugeschlagen und nicht der Widerruf; bei 200 wirkt der "
                        + "Widerruf nicht.");
        assertTrue(p.content().contains(ABWEISUNG),
                wann + ": andere Abweisung als erwartet — " + auszug(p));
        assertEquals(0, p.locator(".w-wrap").count(), wann + ": die Uhr ist trotzdem da");
        assertFalse(p.content().contains(UHR_TITEL), wann + ": Inhalt der Uhr im HTML");
    }

    /** Diese Adresse ist fuer eine Token-Sitzung zu. */
    private void zugesperrt(Page p, String pfad) {
        Response antwort = p.navigate(url(pfad));
        p.waitForLoadState();
        assertNotNull(antwort, "keine Antwort auf " + pfad);
        assertEquals(403, antwort.status(),
                "Eine Token-Sitzung kam auf " + pfad + " mit HTTP " + antwort.status()
                        + " durch (gelandet auf " + p.url() + ") — dann ist der Handy-Link die "
                        + "ganze Sitzung des Besitzers. Haeufigste Ursache: der Filter sieht den "
                        + "Durchgang nicht, weil eine .html-Adresse per forward() zur .xhtml-Sicht "
                        + "kommt und der Filter nur fuer REQUEST angemeldet ist.");
    }

    private String kopfzeile(Page p) {
        Locator h1 = p.locator(".w-head h1");
        return h1.count() == 0 ? "" : h1.innerText().trim();
    }

    private String position(Page p) {
        Locator pos = p.locator(".w-pos");
        return pos.count() == 0 ? "" : pos.innerText().trim();
    }

    private static String auszug(Page p) {
        String html = p.content();
        return html.substring(0, Math.min(900, html.length())).replaceAll("\\s+", " ");
    }

    /**
     * Ein Handy-Link ist ein Dauerausweis. Er steht in keiner Meldung dieser Klasse — auch nicht
     * gekuerzt: ein Praefix im Protokoll ist kein Schutz, sondern nur ein kleinerer Heuhaufen
     * (dieselbe Begruendung wie in {@code WatchHandyLinkService}).
     */
    private static String redigiert(String link) {
        if (link == null) {
            return "null";
        }
        int i = link.indexOf("?t=");
        return i < 0 ? link : link.substring(0, i) + "?t=<redigiert>";
    }
}

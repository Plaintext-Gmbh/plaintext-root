/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import ch.plaintext.ablagen.AblageEintrag;
import ch.plaintext.ablagen.DateiAblage;
import ch.plaintext.ablagen.DateiAblagenRegister;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.testsupport.EmbeddedPg;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.FilePayload;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1440: das Ablage-UI {@code pt:dateiAblage} auf der Seite Root → Ablage ausprobieren, im
 * echten Browser gegen eine Ablage im Speicher. Ein Test, der klickt: eine Seite kann tadellos
 * rendern und trotzdem lautlos keinen Knopf ausführen.
 *
 * <p>Aufbau wie {@link RootPagesPlaywrightIT}; ohne installiertes Chromium überspringt sich die Klasse.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.docker.compose.enabled=false"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// Eigener Kontext (Test-Register): danach schliessen, sonst hält sein Verbindungspool die CI-Postgres
// voll und der nächste IT bekommt «too many clients already» (Pipeline 480, RootPagesPlaywrightIT).
@DirtiesContext
class AblageAusprobierenPlaywrightIT {

    private static final String ROOT_USER = "pw-ablage-root";
    private static final String ADMIN_USER = "pw-ablage-admin";
    private static final String PASSWORT = "Playwright-2026!";
    private static final String WURZEL = "ablage-demo/default/";

    /** Ablage im Speicher statt Nextcloud. */
    static final class Speicher implements DateiAblage {
        final Map<String, byte[]> dateien = new ConcurrentHashMap<>();

        @Override public String name() { return "pw-speicher"; }
        @Override public void schreibe(String pfad, byte[] daten, String typ) { dateien.put(pfad, daten); }
        @Override public byte[] lies(String pfad) throws IOException {
            byte[] b = dateien.get(pfad);
            if (b == null) throw new IOException("fehlt: " + pfad);
            return b;
        }
        @Override public boolean existiert(String pfad) { return dateien.containsKey(pfad); }
        @Override public List<AblageEintrag> liste(String ordner) {
            String p = ordner.isEmpty() ? "" : ordner + "/";
            Map<String, AblageEintrag> l = new java.util.TreeMap<>();
            dateien.forEach((k, v) -> {
                if (!k.startsWith(p)) return;
                String rest = k.substring(p.length());
                int i = rest.indexOf('/');
                l.put(i < 0 ? rest : rest.substring(0, i), i < 0 ? new AblageEintrag(k, false, v.length, null)
                        : new AblageEintrag(p + rest.substring(0, i), true, -1, null));
            });
            return new ArrayList<>(l.values());
        }
        @Override public void loesche(String pfad) { dateien.remove(pfad); }
        @Override public void legeOrdnerAn(String pfad) { dateien.put(pfad + "/.keep", new byte[0]); }
        @Override public void verschiebe(String von, String nach) throws IOException {
            List<String> weg = dateien.keySet().stream().filter(k -> k.equals(von) || k.startsWith(von + "/")).toList();
            if (weg.isEmpty() || dateien.keySet().stream().anyMatch(k -> k.equals(nach) || k.startsWith(nach + "/"))) {
                throw new IOException("verschieben: " + von + " -> " + nach);
            }
            weg.forEach(k -> dateien.put(nach + k.substring(von.length()), dateien.remove(k)));
        }
        @Override public void loescheOrdner(String pfad, boolean rekursiv) throws IOException {
            List<String> drin = dateien.keySet().stream().filter(k -> k.startsWith(pfad + "/")).toList();
            if (drin.isEmpty()) throw new IOException("kein Ordner: " + pfad);
            if (!rekursiv && drin.stream().anyMatch(k -> !k.equals(pfad + "/.keep"))) throw new java.nio.file.DirectoryNotEmptyException(pfad);
            drin.forEach(dateien::remove);
        }
    }

    static final Speicher SPEICHER = new Speicher();

    @TestConfiguration
    static class AblageKonfiguration {
        @Bean
        @Primary
        DateiAblagenRegister testAblagen() {
            return new DateiAblagenRegister() {
                @Override public List<String> namen() { return List.of("pw-speicher"); }
                @Override public Optional<DateiAblage> ablage(String name) {
                    return "pw-speicher".equals(name) ? Optional.of(SPEICHER) : Optional.empty();
                }
            };
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "ablageausprobierenplaywrightit");
    }

    @LocalServerPort int port;
    @Autowired MyUserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

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
        benutzerAnlegen(ROOT_USER, "ROOT", "ADMIN", "USER");
        benutzerAnlegen(ADMIN_USER, "ADMIN", "USER");
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
        user.setMandat("default");
        userRepository.save(user);
    }

    @AfterAll
    void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void newContext() {
        context = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(true));
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    private void anmelden(String benutzer) {
        page.navigate(url("/login.html"));
        page.fill("#username", benutzer);
        page.fill("#password", PASSWORT);
        page.locator("#password").press("Enter");
        page.waitForLoadState();
        assertFalse(page.url().contains("login"), "Login als " + benutzer + " schlug fehl: " + page.url());
    }

    private void ruhig() {
        page.waitForFunction("() => window.jQuery && jQuery.active === 0 && jQuery(':animated').length === 0"
                + " && (!window.PrimeFaces || PrimeFaces.ajax.Queue.isEmpty())");
    }

    private Locator liste() {
        return page.locator("#fm\\:abl-liste");
    }

    @Test
    @DisplayName("ROOT: Ordner durchsuchen, öffnen, herunterladen (attachment + nosniff), hochladen, speichern")
    void durchgang() {
        SPEICHER.dateien.put(WURZEL + "hallo.txt", "Hallo Ablage".getBytes(StandardCharsets.UTF_8));
        SPEICHER.dateien.put(WURZEL + "boese.exe", new byte[]{1});
        SPEICHER.dateien.put(WURZEL + "unter/tief.txt", "tief".getBytes(StandardCharsets.UTF_8));
        SPEICHER.dateien.put("ablage-demo/anderes/fremd.txt", "fremd".getBytes(StandardCharsets.UTF_8));

        List<String> ajaxFehler = new CopyOnWriteArrayList<>();
        List<Map<String, String>> downloadKoepfe = new CopyOnWriteArrayList<>();
        page.onResponse(r -> {
            if (r.headers().getOrDefault("content-disposition", "").contains("hallo.txt")) {
                downloadKoepfe.add(r.headers());
            }
        });
        page.onRequestFinished(anfrage -> {
            if (!"POST".equals(anfrage.method())) return;
            try {
                Response antwort = anfrage.response();
                String rumpf = antwort == null ? "" : antwort.text();
                if (rumpf.startsWith("<?xml") && (rumpf.contains("<error-name>") || rumpf.contains("Exception"))) {
                    ajaxFehler.add(rumpf.length() > 400 ? rumpf.substring(0, 400) : rumpf);
                }
            } catch (RuntimeException _) {
                // Rumpf nicht mehr abrufbar (z.B. Download): kein Befund.
            }
        });

        anmelden(ROOT_USER);
        Response seite = page.navigate(url("/ablage-ausprobieren.html"));
        page.waitForLoadState();
        assertEquals(200, seite.status(), "ablage-ausprobieren.html antwortet nicht mit 200: " + page.url());
        assertEquals(1, page.locator("#fm input[name=_csrf]").count(), "CSRF-Feld fehlt im Formular fm");

        // Die einzige Ablage ist vorgewählt; erlaubte Dateien und Ordner sichtbar, .exe und fremde Mandate nicht.
        String inhalt = liste().innerText();
        assertTrue(inhalt.contains("hallo.txt") && inhalt.contains("unter"), "Liste: " + inhalt);
        assertFalse(inhalt.contains("boese.exe"), "nicht erlaubter Typ sichtbar: " + inhalt);
        assertFalse(inhalt.contains("fremd") || inhalt.contains("anderes"), "fremdes Mandat sichtbar: " + inhalt);

        // Ordner hinein und wieder hoch
        page.locator("#fm\\:abl-liste .pt-ablage-ordner:has-text('unter')").click();
        page.waitForCondition(() -> liste().innerText().contains("tief.txt"));
        assertTrue(page.locator("#fm\\:abl-pfad").innerText().contains("/unter"));
        page.click("#fm\\:abl-hoch");
        page.waitForCondition(() -> liste().innerText().contains("hallo.txt"));

        // Öffnen zeigt den Inhalt in der Vorschau
        Locator zeile = liste().locator("tr:has-text('hallo.txt')");
        zeile.locator(".pt-ablage-oeffnen").click();
        page.locator("#fm\\:vorschauText").waitFor();
        assertEquals("Hallo Ablage", page.locator("#fm\\:vorschauText").innerText());

        // Herunterladen: Inhalt stimmt, attachment, nosniff, kein text/html
        Download d = page.waitForDownload(() -> liste().locator("tr:has-text('hallo.txt') .pt-ablage-herunterladen").click());
        assertEquals("hallo.txt", d.suggestedFilename());
        try (var in = d.createReadStream()) {
            assertArrayEquals("Hallo Ablage".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertFalse(downloadKoepfe.isEmpty(), "keine Download-Antwort gesehen");
        Map<String, String> k = downloadKoepfe.get(0);
        assertTrue(k.get("content-disposition").startsWith("attachment"), "Content-Disposition: " + k);
        assertEquals("nosniff", k.get("x-content-type-options"), "nosniff fehlt: " + k);
        assertEquals("application/octet-stream", k.get("content-type"), "Content-Type: " + k);

        // Hochladen
        page.navigate(url("/ablage-ausprobieren.html"));
        page.waitForLoadState();
        page.setInputFiles("#fm\\:abl-upload_input", new FilePayload("neu.txt", "text/plain",
                "hochgeladen".getBytes(StandardCharsets.UTF_8)));
        page.waitForCondition(() -> liste().innerText().contains("neu.txt"));
        assertArrayEquals("hochgeladen".getBytes(StandardCharsets.UTF_8), SPEICHER.dateien.get(WURZEL + "neu.txt"));

        // Speichern aus dem Textfeld
        page.fill("#fm\\:dateiname", "gespeichert.txt");
        page.fill("#fm\\:text", "aus dem Textfeld");
        page.click("#fm\\:speichern");
        page.waitForCondition(() -> liste().innerText().contains("gespeichert.txt"));
        assertArrayEquals("aus dem Textfeld".getBytes(StandardCharsets.UTF_8), SPEICHER.dateien.get(WURZEL + "gespeichert.txt"));

        // Speichern mit Pfad im Namen: abgelehnt, nichts ausserhalb der Wurzel
        page.fill("#fm\\:dateiname", "../anderes/boese.txt");
        page.click("#fm\\:speichern");
        page.waitForCondition(() -> page.locator("#fm\\:abl-meldungen").innerText().contains("Ungültiger Name"));
        ruhig();
        assertTrue(SPEICHER.dateien.keySet().stream().noneMatch(p -> p.contains("boese.txt")), "Traversal: " + SPEICHER.dateien.keySet());

        assertTrue(ajaxFehler.isEmpty(), "Fehler in Teilantworten: " + ajaxFehler);
    }

    @Test
    @DisplayName("Karte 1475: Ordner anlegen, Ordner verschieben, nicht leeren Ordner erst mit Häkchen löschen (geklickt)")
    void ordnerverwaltung() {
        SPEICHER.dateien.put(WURZEL + "ordner-test/drin.txt", "drin".getBytes(StandardCharsets.UTF_8));
        List<String> ajaxFehler = new CopyOnWriteArrayList<>();
        page.onRequestFinished(anfrage -> {
            if (!"POST".equals(anfrage.method())) return;
            try {
                Response antwort = anfrage.response();
                String rumpf = antwort == null ? "" : antwort.text();
                if (rumpf.startsWith("<?xml") && (rumpf.contains("<error-name>") || rumpf.contains("Exception"))) {
                    ajaxFehler.add(rumpf.length() > 400 ? rumpf.substring(0, 400) : rumpf);
                }
            } catch (RuntimeException _) {
                // Rumpf nicht mehr abrufbar: kein Befund.
            }
        });
        anmelden(ROOT_USER);
        page.navigate(url("/ablage-ausprobieren.html"));
        page.waitForLoadState();

        // Anlegen
        page.fill("#fm\\:abl-ordnername", "neuer-ordner");
        page.click("#fm\\:abl-ordner-anlegen");
        page.waitForCondition(() -> liste().innerText().contains("neuer-ordner"));
        assertTrue(SPEICHER.dateien.containsKey(WURZEL + "neuer-ordner/.keep"), "Ordner nicht angelegt: " + SPEICHER.dateien.keySet());

        // Verschieben eines Ordners in den neuen
        liste().locator("tr:has-text('ordner-test') .pt-ablage-verschieben").click();
        page.locator("#fm\\:abl-ziel").waitFor();
        page.fill("#fm\\:abl-ziel", "neuer-ordner/verschoben");
        page.click("#fm\\:abl-bestaetigen");
        page.waitForCondition(() -> SPEICHER.dateien.containsKey(WURZEL + "neuer-ordner/verschoben/drin.txt"));
        assertFalse(SPEICHER.dateien.containsKey(WURZEL + "ordner-test/drin.txt"));

        // Löschen: ohne Häkchen abgelehnt, mit Häkchen samt Inhalt
        page.waitForCondition(() -> !liste().innerText().contains("ordner-test"));
        liste().locator("tr:has-text('neuer-ordner') .pt-ablage-loeschen").click();
        page.locator("#fm\\:abl-bestaetigen").waitFor();
        page.click("#fm\\:abl-bestaetigen");
        page.waitForCondition(() -> page.locator("#fm\\:abl-meldungen").innerText().contains("nicht leer"));
        ruhig();
        assertTrue(SPEICHER.dateien.containsKey(WURZEL + "neuer-ordner/verschoben/drin.txt"), "ohne Häkchen gelöscht");
        page.locator("#fm\\:abl-mitInhalt .ui-chkbox-box").click();
        page.click("#fm\\:abl-bestaetigen");
        page.waitForCondition(() -> SPEICHER.dateien.keySet().stream().noneMatch(k -> k.startsWith(WURZEL + "neuer-ordner")));
        ruhig();
        assertFalse(liste().innerText().contains("neuer-ordner"), "Liste: " + liste().innerText());
        assertTrue(ajaxFehler.isEmpty(), "Fehler in Teilantworten: " + ajaxFehler);
    }

    @Test
    @DisplayName("ADMIN ohne ROOT: die Seite ist gesperrt (Entscheid worker 10.10.2026)")
    void adminGesperrt() {
        anmelden(ADMIN_USER);
        Response seite = page.navigate(url("/ablage-ausprobieren.html"));
        page.waitForLoadState();
        assertFalse(page.content().contains("abl-liste"), "ADMIN sieht das Ablage-UI");
        assertTrue(seite.status() == 403 || page.url().contains("access-denied") || !page.url().contains("ablage-ausprobieren"),
                "erwartet gesperrt, war " + seite.status() + " " + page.url());
    }
}

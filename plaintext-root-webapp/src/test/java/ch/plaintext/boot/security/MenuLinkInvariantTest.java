/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Startup-Invariante fuer den Seiten-Zugriffsschutz (Karte 308, Punkt 5).
 *
 * <p>Der Guard {@code PageAccessGuardService} leitet den Zugriffsschutz aus der Menue-Sichtbarkeit
 * ab. Damit haengt die Sicherheit einer Seite an einer String-Konvention — und genau daran ist sie
 * gescheitert: {@code MandateMenuBackingBean} deklarierte {@code link = "mandatemenu.xhtml"} statt
 * {@code .html}, der Vergleich fand keinen Treffer, der Guard fiel in den fail-open-Zweig und
 * jeder eingeloggte USER konnte die ROOT-Menuesteuerung aller Mandanten bedienen.
 *
 * <p>Dieser Test macht daraus einen Build-Fehler statt einer Laufzeit-Luecke. Vorbild:
 * {@code CsrfFormInvariantTest} — reiner Quellcode-Scan ueber alle Module, kein Spring-Kontext.
 *
 * <p>Geprueft wird:
 * <ol>
 *   <li>jeder explizit deklarierte {@code @MenuAnnotation(link=...)} endet auf {@code .html},</li>
 *   <li>die Zielview jedes Links existiert als {@code .xhtml} im Repo,</li>
 *   <li>jede ausgelieferte View hat einen Menueeintrag, einen Alias oder einen
 *       Allowlist-/Systemseiten-Eintrag.</li>
 * </ol>
 */
class MenuLinkInvariantTest {

    /** Modul, an dem das Repo-Root erkannt wird. */
    private static final String ANKER_MODUL = "plaintext-root-webapp";

    private static final String JAVA_PFAD = "src/main/java";
    private static final String RESOURCES_PFAD = "src/main/resources/META-INF/resources";

    private static final String ANNOTATION = "@MenuAnnotation";
    private static final Pattern LINK_ATTRIBUT = Pattern.compile("\\blink\\s*=\\s*\"([^\"]*)\"");

    /** Pfadbestandteile, die ein Facelet als Fragment/Template kennzeichnen (keine aufrufbare Seite). */
    private static final List<String> FRAGMENT_MARKER = List.of("/includes/", "/templates/");

    /** Ein {@code ui:composition} OHNE {@code template=} ist ein Include-Fragment, keine Seite. */
    private static final Pattern UI_COMPOSITION = Pattern.compile("<ui:composition\\b([^>]*)>", Pattern.DOTALL);

    /**
     * Menue-Links, die absichtlich auf keine View im Repo zeigen. {@code swagger-ui/index.html} wird
     * von springdoc ausgeliefert, nicht von JSF.
     */
    private static final Set<String> LINKS_OHNE_VIEW = Set.of(
            "swagger-ui/index"
    );

    /**
     * Views, die bewusst KEINE Zugriffsregel bekommen und damit in {@code mode=STRICT} gesperrt sind.
     * Jeder Eintrag hier ist eine Entscheidung, kein Versehen — bitte begruenden.
     */
    private static final Map<String, String> BEWUSST_GESPERRT = Map.of(
            // Derzeit leer.
            //
            // Hier stand bis zum 04.08.2026 "demo" — die verwaiste Google-Charts-Beispielseite, die
            // Karte 308 bewusst gesperrt liegen liess ("Loeschen ist eine eigene Aufraeum-Karte").
            // Das ist Karte 523: demo.xhtml ist geloescht. Der Grund war nicht Ordnungsliebe —
            // plaintext-root-webapp ist Abhaengigkeit JEDER App, die Seite wurde also in app,
            // guild, schuetu, iot und fwtool mit ausgeliefert und war dort, weil diese Apps im
            // Modus REPORT liefen, fuer jeden angemeldeten Benutzer erreichbar.
    );

    /**
     * Systemseiten, Allowlist-Eintraege, Praefixe und Aliase des Guards. Muss mit den Konstanten in
     * {@code PageAccessGuardService} uebereinstimmen — {@link #allowlistUndAliasePassenZumGuard()}
     * prueft genau das.
     */
    private static final Set<String> SYSTEM_UND_ALLOWLIST = Set.of(
            "home", "index", "access-denied", "error", "login",
            "login-totp", "myuser", "useradmin", "menuesteuerung-anleitung"
    );
    private static final Set<String> ALLOW_PREFIXES = Set.of("nosec/");
    private static final Map<String, String> ALIASE = Map.of(
            "mandatemenudetail", "mandatemenu",
            "anforderungdetail", "anforderungen",
            "claudesummary", "anforderungen",
            "howtodetail", "howtos"
    );

    // ------------------------------------------------------------------ Test 1

    @Test
    void jederMenueLinkEndetAufHtml() throws IOException {
        Map<String, String> links = sammleMenueLinks(findeRepoRoot());
        assertFalse(links.isEmpty(), "Keine @MenuAnnotation-Links gefunden — Pfadauflösung prüfen");

        List<String> verstoesse = new ArrayList<>();
        links.forEach((link, quelle) -> {
            if (link.startsWith("/")) {
                // Nicht-JSF-Ziel (absoluter Pfad auf einen Controller/eine andere UI) — nicht Sache
                // des Menue-Guards.
                return;
            }
            if (!link.endsWith(".html")) {
                verstoesse.add(quelle + ": link=\"" + link + "\"");
            }
        });

        if (!verstoesse.isEmpty()) {
            fail("@MenuAnnotation(link=...) muss auf \".html\" enden. Sonst findet der "
                    + "PageAccessGuardService keinen Menuetreffer und die Seite ist "
                    + "ungeschuetzt (genau der Fehler aus Karte 308, H1):\n  "
                    + String.join("\n  ", verstoesse));
        }
    }

    // ------------------------------------------------------------------ Test 2

    @Test
    void jederMenueLinkZeigtAufEineExistierendeView() throws IOException {
        Path repoRoot = findeRepoRoot();
        Map<String, String> links = sammleMenueLinks(repoRoot);
        Set<String> views = sammleViews(repoRoot).keySet();

        List<String> verstoesse = new ArrayList<>();
        links.forEach((link, quelle) -> {
            if (link.startsWith("/")) {
                return;
            }
            String kanonisch = PageAccessGuardService.kanonisch(link);
            if (LINKS_OHNE_VIEW.contains(kanonisch) || views.contains(kanonisch)) {
                return;
            }
            verstoesse.add(quelle + ": link=\"" + link + "\" -> keine View " + kanonisch + ".xhtml gefunden");
        });

        if (!verstoesse.isEmpty()) {
            fail("Menue-Link zeigt auf eine View, die es nicht gibt (toter Link, und der Guard "
                    + "kann die Rollen nirgends durchsetzen):\n  " + String.join("\n  ", verstoesse));
        }
    }

    // ------------------------------------------------------------------ Test 3

    @Test
    void jedeViewHatEineZugriffsregel() throws IOException {
        Path repoRoot = findeRepoRoot();
        Map<String, String> views = sammleViews(repoRoot);
        assertFalse(views.isEmpty(), "Keine Views gefunden — Pfadauflösung prüfen");

        Set<String> menueZiele = new TreeSet<>();
        sammleMenueLinks(repoRoot).keySet().forEach(link -> menueZiele.add(PageAccessGuardService.kanonisch(link)));

        List<String> ungeschuetzt = new ArrayList<>();
        views.forEach((view, datei) -> {
            if (SYSTEM_UND_ALLOWLIST.contains(view) || BEWUSST_GESPERRT.containsKey(view)) {
                return;
            }
            for (String prefix : ALLOW_PREFIXES) {
                if (view.startsWith(prefix)) {
                    return;
                }
            }
            String ziel = ALIASE.getOrDefault(view, view);
            if (menueZiele.contains(ziel)) {
                return;
            }
            ungeschuetzt.add(datei + " (kanonisch: " + view + ")");
        });

        if (!ungeschuetzt.isEmpty()) {
            fail("Diese Views haben keine Zugriffsregel — sie sind im Guard-Modus REPORT fuer "
                    + "JEDEN eingeloggten User offen und im Modus STRICT fuer alle gesperrt. "
                    + "Entweder @MenuAnnotation(link=\"....html\") ergaenzen, oder einen Alias in "
                    + "PageAccessGuardService.FRAMEWORK_ALIASES setzen, oder (mit Begruendung) in "
                    + "MenuLinkInvariantTest.BEWUSST_GESPERRT aufnehmen:\n  "
                    + String.join("\n  ", ungeschuetzt));
        }
    }

    // ------------------------------------------------------------------ Test 4

    @Test
    void allowlistUndAliasePassenZumGuard() {
        // Haelt die Erwartungen dieses Tests und die Konstanten des Guards zusammen: laeuft eine
        // Seite auseinander, faellt es hier auf und nicht erst in PROD.
        for (String seite : SYSTEM_UND_ALLOWLIST) {
            assertTrue(new PageAccessGuardServiceProbe().erlaubtOhneMenue(seite),
                    "Der Guard muss '" + seite + "' ohne Menueeintrag erlauben (Systemseite/Allowlist)");
        }
        for (String prefix : ALLOW_PREFIXES) {
            assertTrue(new PageAccessGuardServiceProbe().erlaubtOhneMenue(prefix + "irgendwas"),
                    "Der Guard muss alles unter '" + prefix + "' erlauben");
        }
        ALIASE.forEach((view, ziel) ->
                assertTrue(new PageAccessGuardServiceProbe().aliasZeigtAuf(view, ziel),
                        "Der Guard muss '" + view + "' wie '" + ziel + "' bewachen"));
    }

    /**
     * Minimal-Sonde auf den Guard: STRICT-Modus, leere Menue-Registry. Erlaubt der Guard eine Seite
     * trotzdem, kommt das aus Systemseiten/Allowlist; ein Alias ist gesetzt, wenn die Seite
     * zusammen mit einem passenden Menueeintrag erlaubt wird.
     */
    private static class PageAccessGuardServiceProbe {

        boolean erlaubtOhneMenue(String seite) {
            return PageAccessGuardTestFactory.strictMitMenues().hasAccessToView("/" + seite + ".xhtml");
        }

        boolean aliasZeigtAuf(String view, String ziel) {
            // Ohne den Ziel-Menueeintrag muss verweigert werden ...
            boolean ohneZiel = PageAccessGuardTestFactory.strictMitMenues().hasAccessToView("/" + view + ".xhtml");
            // ... mit sichtbarem Ziel-Menueeintrag erlaubt.
            boolean mitZiel = PageAccessGuardTestFactory
                    .strictMitMenues(PageAccessGuardTestFactory.menu(ziel + ".html", true))
                    .hasAccessToView("/" + view + ".xhtml");
            return !ohneZiel && mitZiel;
        }
    }

    // ------------------------------------------------------------------ Helfer

    /** Kanonischer View-Name -> Repo-relativer Dateipfad. Fragmente werden ausgelassen. */
    private Map<String, String> sammleViews(Path repoRoot) throws IOException {
        Map<String, String> ergebnis = new TreeMap<>();
        for (Path resourcesDir : verzeichnisse(repoRoot, RESOURCES_PFAD)) {
            try (Stream<Path> stream = Files.walk(resourcesDir)) {
                List<Path> views = stream.filter(p -> p.toString().endsWith(".xhtml")).toList();
                for (Path view : views) {
                    String relativZuResources = resourcesDir.relativize(view).toString().replace('\\', '/');
                    if (FRAGMENT_MARKER.stream().anyMatch(("/" + relativZuResources)::contains)) {
                        continue;
                    }
                    if (istIncludeFragment(Files.readString(view))) {
                        continue;
                    }
                    ergebnis.put(PageAccessGuardService.kanonisch(relativZuResources),
                            repoRoot.relativize(view).toString());
                }
            }
        }
        return ergebnis;
    }

    /**
     * {@code <ui:composition>} ohne {@code template=} ist ein Include-Fragment (wird per
     * {@code ui:include} eingebunden, z.B. {@code menu.xhtml}) und keine aufrufbare Seite.
     */
    private boolean istIncludeFragment(String inhalt) {
        Matcher composition = UI_COMPOSITION.matcher(inhalt);
        if (!composition.find()) {
            return false;
        }
        return !composition.group(1).contains("template=");
    }

    /** Deklarierter Link -> "Modul/Klasse". Nur explizit gesetzte {@code link}-Attribute. */
    private Map<String, String> sammleMenueLinks(Path repoRoot) throws IOException {
        Map<String, String> ergebnis = new LinkedHashMap<>();
        for (Path javaDir : verzeichnisse(repoRoot, JAVA_PFAD)) {
            try (Stream<Path> stream = Files.walk(javaDir)) {
                List<Path> dateien = stream.filter(p -> p.toString().endsWith(".java")).toList();
                for (Path datei : dateien) {
                    String inhalt = Files.readString(datei);
                    for (String attribute : annotationsBloecke(inhalt)) {
                        Matcher link = LINK_ATTRIBUT.matcher(attribute);
                        if (link.find() && !link.group(1).isBlank()) {
                            ergebnis.put(link.group(1), repoRoot.relativize(datei).toString());
                        }
                    }
                }
            }
        }
        return ergebnis;
    }

    /**
     * Attributbloecke aller {@code @MenuAnnotation(...)} einer Datei. Bewusst ein Klammer-Zaehler
     * statt einer Regex: {@code RootApiTokenMenu} hat {@code title = "API Tokens (Root)"} — ein
     * {@code [^)]*}-Muster bricht dort mitten im String ab und der Link wird nicht gefunden.
     */
    private List<String> annotationsBloecke(String inhalt) {
        List<String> ergebnis = new ArrayList<>();
        int suchStart = 0;
        while (true) {
            int start = inhalt.indexOf(ANNOTATION, suchStart);
            if (start < 0) {
                return ergebnis;
            }
            suchStart = start + ANNOTATION.length();
            int klammerAuf = inhalt.indexOf('(', suchStart);
            if (klammerAuf < 0) {
                return ergebnis;
            }
            // Nur Whitespace zwischen Annotationsnamen und Klammer erlaubt (sonst ist es ein
            // Import, ein Javadoc-Verweis oder eine Annotation ohne Attribute).
            if (!inhalt.substring(suchStart, klammerAuf).isBlank()) {
                continue;
            }
            int tiefe = 0;
            boolean inString = false;
            for (int i = klammerAuf; i < inhalt.length(); i++) {
                char zeichen = inhalt.charAt(i);
                if (inString) {
                    if (zeichen == '\\') {
                        i++;
                    } else if (zeichen == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (zeichen == '"') {
                    inString = true;
                } else if (zeichen == '(') {
                    tiefe++;
                } else if (zeichen == ')') {
                    tiefe--;
                    if (tiefe == 0) {
                        ergebnis.add(inhalt.substring(klammerAuf + 1, i));
                        suchStart = i + 1;
                        break;
                    }
                }
            }
        }
    }

    private List<Path> verzeichnisse(Path repoRoot, String unterPfad) throws IOException {
        List<Path> ergebnis = new ArrayList<>();
        try (Stream<Path> module = Files.list(repoRoot)) {
            module.filter(Files::isDirectory)
                    .map(modul -> modul.resolve(unterPfad))
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(ergebnis::add);
        }
        return ergebnis;
    }

    /** Repo-Root ueber das Ankermodul finden (Test laeuft mit wechselndem working directory). */
    private Path findeRepoRoot() {
        Path kandidat = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && kandidat != null; i++) {
            if (Files.isDirectory(kandidat.resolve(ANKER_MODUL))) {
                return kandidat;
            }
            kandidat = kandidat.getParent();
        }
        throw new IllegalStateException("Repo-Root (Verzeichnis mit " + ANKER_MODUL + ") nicht gefunden");
    }
}

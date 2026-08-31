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
 * Startup invariant for the page access protection (card 308, item 5).
 *
 * <p>The guard {@code PageAccessGuardService} derives the access protection from the menu
 * visibility. The security of a page therefore hangs on a string convention — and on exactly that it
 * failed: {@code MandateMenuBackingBean} declared {@code link = "mandatemenu.xhtml"} instead of
 * {@code .html}, the comparison found no match, the guard fell into the fail-open branch and
 * every logged-in USER could operate the ROOT menu control of all tenants.
 *
 * <p>This test turns that into a build error instead of a runtime gap. Model:
 * {@code CsrfFormInvariantTest} — a pure source scan across all modules, no Spring context.
 *
 * <p>Checked is:
 * <ol>
 *   <li>every explicitly declared {@code @MenuAnnotation(link=...)} ends in {@code .html},</li>
 *   <li>the target view of every link exists as an {@code .xhtml} in the repository,</li>
 *   <li>every shipped view has a menu entry, an alias or an
 *       allowlist/system-page entry.</li>
 * </ol>
 */
class MenuLinkInvariantTest {

    /** Module by which the repository root is recognized. */
    private static final String ANKER_MODUL = "plaintext-root-webapp";

    private static final String JAVA_PFAD = "src/main/java";
    private static final String RESOURCES_PFAD = "src/main/resources/META-INF/resources";

    private static final String ANNOTATION = "@MenuAnnotation";
    private static final Pattern LINK_ATTRIBUT = Pattern.compile("\\blink\\s*=\\s*\"([^\"]*)\"");

    /** Path components that mark a facelet as a fragment/template (not a callable page). */
    private static final List<String> FRAGMENT_MARKER = List.of("/includes/", "/templates/");

    /** A {@code ui:composition} WITHOUT {@code template=} is an include fragment, not a page. */
    private static final Pattern UI_COMPOSITION = Pattern.compile("<ui:composition\\b([^>]*)>", Pattern.DOTALL);

    /**
     * Menu links that deliberately point to no view in the repository. {@code swagger-ui/index.html} is
     * served by springdoc, not by JSF.
     */
    private static final Set<String> LINKS_OHNE_VIEW = Set.of(
            "swagger-ui/index"
    );

    /**
     * Views that deliberately get NO access rule and are therefore blocked in {@code mode=STRICT}.
     * Every entry here is a decision, not an oversight — please give a justification.
     */
    private static final Map<String, String> BEWUSST_GESPERRT = Map.of(
            // Currently empty.
            //
            // Until 04.08.2026 "demo" stood here — the orphaned Google Charts example page that
            // card 308 deliberately left blocked ("deleting it is a cleanup card of its own").
            // That is card 523: demo.xhtml has been deleted. The reason was not tidiness —
            // plaintext-root-webapp is a dependency of EVERY app, so the page was shipped in app,
            // guild, schuetu, iot and fwtool as well and was reachable there for every logged-in
            // user, because those apps ran in mode REPORT.
    );

    /**
     * System pages, allowlist entries, prefixes and aliases of the guard. Has to agree with the
     * constants in {@code PageAccessGuardService} — {@link #allowlistUndAliasePassenZumGuard()}
     * checks exactly that.
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
                // Non-JSF target (absolute path to a controller / another UI) — not the business
                // of the menu guard.
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
        // Keeps the expectations of this test and the constants of the guard together: if a
        // page drifts apart, it shows up here and not only in PROD.
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
     * Minimal probe on the guard: STRICT mode, empty menu registry. If the guard permits a page
     * nevertheless, that comes from system pages/allowlist; an alias is set if the page is
     * permitted together with a matching menu entry.
     */
    private static class PageAccessGuardServiceProbe {

        boolean erlaubtOhneMenue(String seite) {
            return PageAccessGuardTestFactory.strictMitMenues().hasAccessToView("/" + seite + ".xhtml");
        }

        boolean aliasZeigtAuf(String view, String ziel) {
            // Without the target menu entry access has to be denied ...
            boolean ohneZiel = PageAccessGuardTestFactory.strictMitMenues().hasAccessToView("/" + view + ".xhtml");
            // ... with a visible target menu entry it is permitted.
            boolean mitZiel = PageAccessGuardTestFactory
                    .strictMitMenues(PageAccessGuardTestFactory.menu(ziel + ".html", true))
                    .hasAccessToView("/" + view + ".xhtml");
            return !ohneZiel && mitZiel;
        }
    }

    // ----------------------------------------------------------------- Helpers

    /** Canonical view name -> repository-relative file path. Fragments are skipped. */
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
     * A {@code <ui:composition>} without {@code template=} is an include fragment (pulled in via
     * {@code ui:include}, e.g. {@code menu.xhtml}) and not a callable page.
     */
    private boolean istIncludeFragment(String inhalt) {
        Matcher composition = UI_COMPOSITION.matcher(inhalt);
        if (!composition.find()) {
            return false;
        }
        return !composition.group(1).contains("template=");
    }

    /** Declared link -> "module/class". Only explicitly set {@code link} attributes. */
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
     * Attribute blocks of all {@code @MenuAnnotation(...)} of a file. Deliberately a bracket counter
     * instead of a regex: {@code RootApiTokenMenu} has {@code title = "API Tokens (Root)"} — a
     * {@code [^)]*} pattern breaks off in the middle of that string and the link is not found.
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
            // Only whitespace permitted between the annotation name and the bracket (otherwise it is an
            // import, a Javadoc reference or an annotation without attributes).
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

    /** Find the repository root via the anchor module (the test runs with a changing working directory). */
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

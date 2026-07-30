/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Stellt sicher, dass jedes h:form in allen Modulen des Repos ein _csrf-Hidden-Input besitzt.
 * <p>
 * Seit dem Entfernen der CSRF-Ignore-Patterns {@code /**&#47;*.xhtml} und {@code /**&#47;*.html}
 * aus {@code PlaintextSecurityConfig.DEFAULT_CSRF_IGNORE} validiert Spring Security jeden
 * JSF-POST. Ein h:form ohne {@code <input type="hidden" name="_csrf" value="#{_csrf.token}"/>}
 * schlägt dann beim Submit (auch PrimeFaces-AJAX) mit HTTP 403 fehl.
 * <p>
 * Analog zum CsrfFormInvariantTest in plaintext-iot, aber repo-weit: Es werden dynamisch alle
 * Module gescannt, die ein src/main/resources/META-INF/resources-Verzeichnis besitzen.
 */
class CsrfFormInvariantTest {

    private static final Pattern FORM_START = Pattern.compile("<h:form[^>]*>");
    private static final Pattern CSRF_INPUT = Pattern.compile("name=[\"']_csrf[\"']");

    /** Modul, an dem das Repo-Root erkannt wird. */
    private static final String ANKER_MODUL = "plaintext-root-webapp";

    private static final String RESOURCES_PFAD = "src/main/resources/META-INF/resources";

    @Test
    void alleFormsHabenCsrfHiddenInput() throws IOException {
        List<String> verstoesse = new ArrayList<>();
        List<Path> xhtmlDateien = new ArrayList<>();

        Path repoRoot = findeRepoRoot();
        List<Path> resourceVerzeichnisse = findeResourceVerzeichnisse(repoRoot);
        assertTrue(!resourceVerzeichnisse.isEmpty(),
                "Keine META-INF/resources-Verzeichnisse gefunden — Pfadauflösung prüfen (Repo-Root: " + repoRoot + ")");

        for (Path root : resourceVerzeichnisse) {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".xhtml")).forEach(xhtmlDateien::add);
            }
        }

        xhtmlDateien.forEach(xhtml -> prüfeXhtml(repoRoot, xhtml, verstoesse));

        if (xhtmlDateien.isEmpty()) {
            fail("Keine XHTML-Dateien gefunden — Pfadauflösung prüfen (Repo-Root: " + repoRoot + ")");
        }
        if (!verstoesse.isEmpty()) {
            fail("h:form ohne _csrf-Hidden-Input gefunden (führt seit CSRF-Validierung auf *.xhtml zu 403 beim Submit):\n"
                    + String.join("\n", verstoesse));
        }
    }

    /** Alle Modul-Verzeichnisse mit JSF-Resources (direkt unterhalb des Repo-Roots). */
    private List<Path> findeResourceVerzeichnisse(Path repoRoot) throws IOException {
        List<Path> ergebnis = new ArrayList<>();
        try (Stream<Path> module = Files.list(repoRoot)) {
            module.filter(Files::isDirectory)
                    .map(modul -> modul.resolve(RESOURCES_PFAD))
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(ergebnis::add);
        }
        return ergebnis;
    }

    private void prüfeXhtml(Path repoRoot, Path xhtml, List<String> verstoesse) {
        String inhalt;
        try {
            inhalt = Files.readString(xhtml);
        } catch (IOException e) {
            throw new RuntimeException("Kann " + xhtml + " nicht lesen", e);
        }

        Matcher formMatcher = FORM_START.matcher(inhalt);
        while (formMatcher.find()) {
            int formStart = formMatcher.end();
            // Suche endendes </h:form> ab der Form-Öffnung
            int formEnd = inhalt.indexOf("</h:form>", formStart);
            if (formEnd < 0) formEnd = inhalt.length();
            String formBody = inhalt.substring(formStart, formEnd);

            if (!CSRF_INPUT.matcher(formBody).find()) {
                int zeile = inhalt.substring(0, formMatcher.start()).split("\n", -1).length;
                verstoesse.add("  " + repoRoot.relativize(xhtml) + ":" + zeile
                        + " — " + formMatcher.group() + " hat kein _csrf-Hidden-Input");
            }
        }
    }

    /**
     * Findet das Repo-Root unabhängig vom user.dir-Kontext (Maven aus Modul oder Repo-Root, IDE):
     * läuft von der Test-Class-Location (target/test-classes) bzw. von user.dir aufwärts,
     * bis ein Verzeichnis das Anker-Modul enthält.
     */
    private Path findeRepoRoot() {
        List<Path> startpunkte = new ArrayList<>();
        try {
            startpunkte.add(Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (URISyntaxException | RuntimeException _) {
            // z.B. exotischer ClassLoader — dann greift der user.dir-Fallback
        }
        startpunkte.add(Path.of(System.getProperty("user.dir")));

        for (Path start : startpunkte) {
            for (Path dir = start.toAbsolutePath(); dir != null; dir = dir.getParent()) {
                if (Files.isDirectory(dir.resolve(ANKER_MODUL))) {
                    return dir;
                }
            }
        }
        throw new IllegalStateException("Repo-Root nicht gefunden (Startpunkte: " + startpunkte
                + ") — Pfadauflösung prüfen");
    }
}

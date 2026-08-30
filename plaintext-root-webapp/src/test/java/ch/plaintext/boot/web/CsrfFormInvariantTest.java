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
 * Makes sure that every h:form in all modules of the repository has a _csrf hidden input.
 * <p>
 * Since the removal of the CSRF ignore patterns {@code /**&#47;*.xhtml} and {@code /**&#47;*.html}
 * from {@code PlaintextSecurityConfig.DEFAULT_CSRF_IGNORE}, Spring Security validates every
 * JSF POST. An h:form without {@code <input type="hidden" name="_csrf" value="#{_csrf.token}"/>}
 * then fails on submit (PrimeFaces AJAX included) with HTTP 403.
 * <p>
 * Analogous to the CsrfFormInvariantTest in plaintext-iot, but repository-wide: all
 * modules that have a src/main/resources/META-INF/resources directory are scanned dynamically.
 */
class CsrfFormInvariantTest {

    private static final Pattern FORM_START = Pattern.compile("<h:form[^>]*>");
    private static final Pattern CSRF_INPUT = Pattern.compile("name=[\"']_csrf[\"']");

    /** Module by which the repository root is recognized. */
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

    /** All module directories with JSF resources (directly below the repository root). */
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
            // Search for the closing </h:form> starting from the form opening
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
     * Finds the repository root independently of the user.dir context (Maven from a module or from the
     * repository root, IDE): walks upwards from the test class location (target/test-classes) resp.
     * from user.dir until a directory contains the anchor module.
     */
    private Path findeRepoRoot() {
        List<Path> startpunkte = new ArrayList<>();
        try {
            startpunkte.add(Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (URISyntaxException | RuntimeException _) {
            // e.g. an exotic class loader — then the user.dir fallback applies
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

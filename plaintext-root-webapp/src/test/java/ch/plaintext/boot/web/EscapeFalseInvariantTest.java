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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Permanent guardrail against stored XSS in Facelets (SECURITY card 304): in no view of the
 * repository may {@code escape="false"} appear.
 * <p>
 * Background: {@code escape="false"} writes the value raw into the response. If a
 * database/user value stands there (user name, tenant, i18n translation, LLM output), it turns into
 * stored XSS — preferably on admin pages such as {@code useradmin.xhtml}, i.e. in the highest
 * privilege context. The CSP does not catch that as long as it permits {@code 'unsafe-inline'}.
 * <p>
 * With card 304 all 59 occurrences were removed: not a single place needed real HTML
 * (cron help texts, labels, user names, field names, i18n strings). That is why a
 * <b>complete ban</b> is formulated here instead of a heuristic on EL patterns such as {@code #{obj.}
 * — a ban cannot be circumvented by a new expression name ({@code #{row.},
 * #{field.} …}).
 * <p>
 * <b>Opt-out</b> for the case that a view really has to render HTML: write the marker
 * {@code escape-false-ok} as a comment on the same line, together with the justification of
 * where the HTML comes from. Then the rule is: the content must be sanitized on the server side (jsoup
 * {@code Safelist} is available as a dependency) — escaping is the default answer,
 * sanitizing the justified exception.
 * <p>
 * Structure analogous to {@link CsrfFormInvariantTest}: repository-wide scan of all modules with
 * {@code src/main/resources/META-INF/resources}.
 */
class EscapeFalseInvariantTest {

    /** {@code escape="false"} in every spelling (whitespace, single/double quotes, case). */
    private static final Pattern ESCAPE_FALSE =
            Pattern.compile("escape\\s*=\\s*[\"']\\s*false\\s*[\"']", Pattern.CASE_INSENSITIVE);

    /** XML comment (also multi-line) — hidden before the scan, so that documentation text
     *  about the ban (and commented-out legacy code) causes no false alarm. */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** Opt-out marker as a comment on the same line. */
    private static final String EXEMPT_COMMENT = "escape-false-ok";

    /** Module by which the repository root is recognized. */
    private static final String ANKER_MODUL = "plaintext-root-webapp";

    private static final String RESOURCES_PFAD = "src/main/resources/META-INF/resources";

    @Test
    void keineViewRendertUnescaped() throws IOException {
        List<String> verstoesse = new ArrayList<>();
        List<Path> viewDateien = new ArrayList<>();

        Path repoRoot = findeRepoRoot();
        List<Path> resourceVerzeichnisse = findeResourceVerzeichnisse(repoRoot);
        assertTrue(!resourceVerzeichnisse.isEmpty(),
                "Keine META-INF/resources-Verzeichnisse gefunden — Pfadauflösung prüfen (Repo-Root: " + repoRoot + ")");

        for (Path root : resourceVerzeichnisse) {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> {
                    String name = p.toString();
                    return name.endsWith(".xhtml") || name.endsWith(".html");
                }).forEach(viewDateien::add);
            }
        }

        if (viewDateien.isEmpty()) {
            fail("Keine View-Dateien gefunden — Pfadauflösung prüfen (Repo-Root: " + repoRoot + ")");
        }

        for (Path view : viewDateien) {
            for (String treffer : findeTreffer(Files.readString(view))) {
                verstoesse.add("  " + repoRoot.relativize(view) + ":" + treffer);
            }
        }

        if (!verstoesse.isEmpty()) {
            fail("escape=\"false\" gefunden — Stored-XSS-Risiko, sobald dort ein DB-/Benutzerwert landet.\n"
                    + "Attribut entfernen (JSF escapt dann per Default). Braucht die Stelle wirklich HTML:\n"
                    + "serverseitig mit jsoup sanitisieren und den Marker '" + EXEMPT_COMMENT
                    + "' mit Begründung in dieselbe Zeile schreiben.\n"
                    + String.join("\n", verstoesse));
        }
    }

    /**
     * Self-test of the detection: prevents the scan from silently becoming a no-op through a broken
     * regex, which would make the guardrail fall away unnoticed.
     */
    @Test
    void erkennungFunktioniert() {
        String inhalt = """
                <h:outputText value="#{obj.username}" escape="false"/>
                <h:outputText value="#{obj.mandat}" escape = 'FALSE'/>
                <h:outputText value="#{obj.startpage}" escape="false"/> <!-- escape-false-ok: sanitisiert -->
                <h:outputText value="#{obj.id}"/>
                <h:outputText value="#{obj.x}" escape="true"/>
                <!-- Doku: hier stand mal escape="false" -->
                <!-- mehrzeiliger Kommentar
                     mit escape="false" drin -->
                """;
        List<String> treffer = findeTreffer(inhalt);
        assertEquals(2, treffer.size(),
                "Erwartet: Zeile 1 und 2 als Verstoss; Zeile 3 per Opt-out befreit, 4+5 unauffällig, "
                        + "6-8 in Kommentaren. War: " + treffer);
        assertTrue(treffer.get(0).startsWith("1 "), "Erster Treffer muss Zeile 1 sein, war: " + treffer.get(0));
        assertTrue(treffer.get(1).startsWith("2 "), "Zweiter Treffer muss Zeile 2 sein, war: " + treffer.get(1));
    }

    /**
     * Finds all {@code escape="false"} that are not exempted and returns "&lt;zeile&gt; — &lt;zeileninhalt&gt;".
     * <p>Detection runs on the comment-free text (documentation/commented-out code does not count),
     * whereas the opt-out marker is looked for on the original line — by definition it stands in a
     * comment.
     */
    private List<String> findeTreffer(String inhalt) {
        List<String> treffer = new ArrayList<>();
        String[] original = inhalt.split("\n", -1);
        String[] ohneKommentare = kommentareAusblenden(inhalt).split("\n", -1);
        for (int i = 0; i < ohneKommentare.length; i++) {
            Matcher matcher = ESCAPE_FALSE.matcher(ohneKommentare[i]);
            if (matcher.find() && !original[i].contains(EXEMPT_COMMENT)) {
                treffer.add((i + 1) + " — " + original[i].strip());
            }
        }
        return treffer;
    }

    /** Replaces the content of XML comments with blanks; the line structure is preserved. */
    private String kommentareAusblenden(String inhalt) {
        StringBuilder sb = new StringBuilder(inhalt);
        Matcher matcher = COMMENT.matcher(inhalt);
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                if (sb.charAt(i) != '\n') {
                    sb.setCharAt(i, ' ');
                }
            }
        }
        return sb.toString();
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

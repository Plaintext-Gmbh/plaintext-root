/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import ch.plaintext.boot.utils.FaceletsElLinter;
import ch.plaintext.boot.utils.FaceletsElLinter.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Linter-Guard gegen den Facelets-EL-Fallstrick in ALLEN Framework-XHTML von plaintext-root.
 *
 * <p>Ausloeser (real in {@code setup.xhtml} aufgetreten): ein gerades {@code "} in einem inline-EL
 * im Body-Text — z. B. {@code #{i18n.t('... „Global – System" ...')}} — sprengt den Facelets-
 * Textparser ({@code ELText.findVarLength}) mit "EL Expression Unbalanced" und die komplette Seite
 * endet in einem 500 / Whitelabel-Error. Der Test verhindert, dass so etwas erneut unbemerkt
 * hinzukommt.
 *
 * <p>Der Scan-Code lebt in {@link FaceletsElLinter} (in plaintext-root-common) und ist damit
 * transitiv auf dem Test-Classpath jedes abhaengigen Projekts (app, iot, fwtool, schuetu), das
 * diesen Test nach dem naechsten root-Release 1:1 uebernehmen kann.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class FaceletsElLinterTest {

    private static final String RESOURCES_SUFFIX = "src/main/resources/META-INF/resources";

    /**
     * Scannt jedes {@code src/main/resources/META-INF/resources} aller root-Module (ab Repo-Wurzel)
     * und schlaegt bei jedem geraden {@code "} in einem inline-Body-EL mit Datei + Zeile fehl.
     */
    @Test
    void keinGeradesQuoteInInlineElVonFrameworkXhtml() throws IOException {
        List<Path> resourceRoots = findResourceRoots();
        assertTrue(!resourceRoots.isEmpty(),
                "Keine META-INF/resources-Verzeichnisse gefunden (cwd="
                        + Path.of("").toAbsolutePath() + ")");

        List<Violation> violations = new ArrayList<>();
        for (Path root : resourceRoots) {
            violations.addAll(FaceletsElLinter.scan(root));
        }

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder("""
                    \n
                    === FACELETS-EL-FALLSTRICK: gerades " in inline-#{...} im Body-Text ===
                    (bricht ELText.findVarLength -> 500/Whitelabel auf der ganzen Seite)
                    """);
            for (Violation v : violations) {
                msg.append("  ! ").append(v).append("\n");
            }
            msg.append("\nFix: im EL-String deutsche Typografie-Quotes („ “) statt geradem \" verwenden.\n");
            fail(msg.toString());
        }
    }

    @Test
    void linterErkenntGeradesQuoteUndRespektiertKontextUndOptOut(@TempDir Path tmp) throws IOException {
        Path res = Files.createDirectories(tmp.resolve("META-INF/resources"));

        // ECHTE Verstoesse: gerades " in inline-Body-EL.
        Files.writeString(res.resolve("bad.xhtml"),
                "<small>#{i18n.t('Sichtbarkeit „Global – System\" in der Mailbox')}</small>");
        Files.writeString(res.resolve("badMultiline.xhtml"),
                "<h:panelGroup>\n    #{i18n.t('Lege ein Konto mit Sichtbarkeit „X\" an')}\n</h:panelGroup>");

        // KEINE Verstoesse:
        // (a) EL im Attributwert – dort ist " der Attribut-Delimiter, harmlos und ueberall ueblich.
        Files.writeString(res.resolve("okAttr.xhtml"),
                "<p:confirm header=\"#{i18n.t('Bestätigung')}\" message=\"#{i18n.t('Wirklich löschen?')}\"/>");
        // (b) Body-EL mit korrekten deutschen Typografie-Quotes.
        Files.writeString(res.resolve("okTypografie.xhtml"),
                "<small>#{i18n.t('Sichtbarkeit „Global – System“ in der Mailbox')}</small>");
        // (c) Gerades " im normalen Body-Text ausserhalb eines EL-Ausdrucks.
        Files.writeString(res.resolve("okPlainText.xhtml"),
                "<small>Sichtbarkeit \"Global\" ist ok #{bean.wert}</small>");
        // (d) Begruendetes Opt-out in derselben Zeile.
        Files.writeString(res.resolve("okOptOut.xhtml"),
                "<small>#{i18n.t('Rest \" Rest')}</small> <!-- el-quote-ok -->");

        List<Violation> violations = FaceletsElLinter.scan(res);

        assertEquals(2, violations.size(),
                "Erwartet genau 2 Verstoesse (bad + badMultiline), gefunden: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.file().getFileName().toString().equals("bad.xhtml")));
        assertTrue(violations.stream().anyMatch(v -> v.file().getFileName().toString().equals("badMultiline.xhtml")));
    }

    @Test
    void scanAufNichtVorhandenemPfadLiefertLeereListe() {
        assertTrue(FaceletsElLinter.scan(Path.of("does/not/exist/xyz")).isEmpty());
        assertTrue(FaceletsElLinter.scan(null).isEmpty());
    }

    /**
     * Findet ab dem Arbeitsverzeichnis nach oben die Repo-Wurzel und sammelt jedes
     * {@code <modul>/src/main/resources/META-INF/resources}. Faellt auf das eigene Modul zurueck,
     * falls die Wurzel nicht gefunden wird (z. B. isolierter Modul-Build).
     */
    private static List<Path> findResourceRoots() throws IOException {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        List<Path> roots = new ArrayList<>();
        Path own = start.resolve(RESOURCES_SUFFIX);
        if (Files.isDirectory(own)) {
            roots.add(own);
        }

        Path repoRoot = findRepoRoot(start);
        if (repoRoot != null) {
            try (Stream<Path> modules = Files.list(repoRoot)) {
                modules.filter(Files::isDirectory)
                       .map(m -> m.resolve(RESOURCES_SUFFIX))
                       .filter(Files::isDirectory)
                       .filter(p -> !roots.contains(p))
                       .forEach(roots::add);
            }
        }
        return roots;
    }

    /** Repo-Wurzel = erstes Verzeichnis nach oben, das einen Maven-Reactor (pom.xml mit &lt;modules&gt;) hat. */
    private static Path findRepoRoot(Path start) throws IOException {
        Path dir = start;
        for (int i = 0; i < 8 && dir != null; i++) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.readString(pom).contains("<modules>")) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }
}

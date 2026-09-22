/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared source-code guard against {@code jakarta.faces.view.ViewScoped} in ALL
 * {@code src/main/java} of the respective reactor.
 *
 * <p>Background: the backing beans were migrated from {@code @ViewScoped} to session-scoped
 * ({@code @Component} + {@code @Scope("session")} + preRenderView listener {@code #{bean.onLoad()}} with
 * an {@code isPostback} guard). This rule prevents the JSF view-scope annotation from being
 * reintroduced in the future.
 *
 * <p><b>Why a source-code scan instead of (only) ArchUnit?</b> The ArchUnit bytecode scan in
 * {@link PlaintextArchitectureTest} ({@code keineViewScopedBeans}) only sees classes on the
 * classpath. Reactor modules that hang off no webapp (e.g. {@code plaintext-admin-requirements}
 * in root) would lie outside the ArchUnit coverage. This scan runs from the reactor root over
 * EVERY module's {@code src/main/java} and closes that gap. Mentions in Javadoc or comments are
 * deliberately NOT counted as a violation.
 *
 * <p>Like the other classes of this module the test lives in {@code src/main/java} of
 * {@code plaintext-root-archtests} and runs inside a consumer via Surefire {@code <dependenciesToScan>}
 * against that consumer's source code.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextViewScopedBanTest {

    private static final String JAVA_SUFFIX = "src/main/java";

    /**
     * Lower bound for the scan set (measured, see {@link ReactorLayout#untergrenze}). Smallest of
     * the six reactors on 22.09.2026 — schuetu and iot with two {@code src/main/java} roots each
     * (root 24, app 31, guild 8, fwtool 5).
     */
    private static final int MINDESTENS_SCANWURZELN = 2;
    private static final String IMPORT_TOKEN = "import jakarta.faces.view.ViewScoped";
    private static final String FQ_ANNOTATION = "@jakarta.faces.view.ViewScoped";
    private static final String SHORT_ANNOTATION = "@ViewScoped";

    @Test
    void keineViewScopedNutzungInFrameworkQuelltext() throws IOException {
        List<Path> sourceRoots = findJavaSourceRoots();
        ReactorLayout.untergrenze(sourceRoots, MINDESTENS_SCANWURZELN, JAVA_SUFFIX);

        List<String> violations = new ArrayList<>();
        for (Path root : sourceRoots) {
            violations.addAll(scanForViewScoped(root));
        }

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                    "\n\n=== VERBOTENE ViewScoped-Nutzung ===\n");
            violations.forEach(v -> msg.append("  ! ").append(v).append("\n"));
            msg.append("\nBacking-Beans laufen session-scoped: @Component + @Scope(\"session\") und ein\n")
               .append("preRenderView-Listener #{bean.onLoad()} mit isPostback-Guard statt der JSF-View-Scope-\n")
               .append("Annotation. Bitte die Bean entsprechend umstellen.\n");
            fail(msg.toString());
        }
    }

    @Test
    void scannerErkenntAnnotationUndIgnoriertJavadoc(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("pkg"));
        Files.writeString(dir.resolve("BadImport.java"),
                "package pkg;\nimport jakarta.faces.view.ViewScoped;\n@ViewScoped\npublic class BadImport {}\n");
        Files.writeString(dir.resolve("BadFq.java"),
                "package pkg;\n@jakarta.faces.view.ViewScoped\npublic class BadFq {}\n");
        Files.writeString(dir.resolve("GoodJavadoc.java"),
                "package pkg;\n/**\n * preRenderView-Listener (session-scoped statt @ViewScoped)\n */\n"
                        + "public class GoodJavadoc {}\n");
        Files.writeString(dir.resolve("GoodLineComment.java"),
                "package pkg;\npublic class GoodLineComment {\n    // abgeloest: kein @ViewScoped mehr\n}\n");

        List<String> hits = scanForViewScoped(tmp);

        assertTrue(hits.stream().anyMatch(h -> h.contains("BadImport.java")),
                "BadImport.java (import + @ViewScoped) muss erkannt werden: " + hits);
        assertTrue(hits.stream().anyMatch(h -> h.contains("BadFq.java")),
                "BadFq.java (voll qualifizierte Annotation) muss erkannt werden: " + hits);
        assertFalse(hits.stream().anyMatch(h -> h.contains("GoodJavadoc.java")),
                "Javadoc-Erwaehnung darf NICHT als Verstoss zaehlen: " + hits);
        assertFalse(hits.stream().anyMatch(h -> h.contains("GoodLineComment.java")),
                "Zeilenkommentar-Erwaehnung darf NICHT als Verstoss zaehlen: " + hits);
    }

    @Test
    void scanAufNichtVorhandenemPfadLiefertLeereListe() throws IOException {
        assertTrue(scanForViewScoped(Path.of("does/not/exist/xyz")).isEmpty());
        assertTrue(scanForViewScoped(null).isEmpty());
    }

    /**
     * Scans every {@code *.java} file below {@code root} and reports real use of the ViewScoped
     * annotation (import or annotation), but NO occurrences in line or block comments.
     */
    static List<String> scanForViewScoped(Path root) throws IOException {
        List<String> hits = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return hits;
        }
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> javaFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
            for (Path f : javaFiles) {
                hits.addAll(scanFile(f));
            }
        }
        return hits;
    }

    private static List<String> scanFile(Path f) throws IOException {
        List<String> hits = new ArrayList<>();
        int lineNo = 0;
        boolean inBlockComment = false;
        for (String raw : Files.readAllLines(f)) {
            lineNo++;
            String line = raw.strip();
            if (inBlockComment) {
                if (line.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (line.startsWith("//") || line.startsWith("*")) {
                continue;
            }
            if (line.startsWith("/*")) {
                if (!line.contains("*/")) {
                    inBlockComment = true;
                }
                continue;
            }
            if (line.contains(IMPORT_TOKEN) || line.contains(FQ_ANNOTATION) || line.contains(SHORT_ANNOTATION)) {
                hits.add(f + ":" + lineNo + "  " + line);
            }
        }
        return hits;
    }

    /**
     * All {@code <modul>/src/main/java} of the reactor — from {@link ReactorLayout#sourceRoots},
     * no longer an own copy.
     *
     * <p><b>Karte 1294, 22.09.2026:</b> until then this class carried a <b>flat</b>
     * {@code Files.list(repoRoot)}. A nested module ({@code gruppe/modul/src/main/java}) was
     * invisible to it — the linter was green because it did not know the directory, not because
     * the directory was clean ({@code FlachGegenRekursivTest} shows both in one run).
     * {@link ReactorLayout#sourceRoots} descends up to five levels, skips
     * {@code target}/{@code .git}/{@code node_modules} and leaves out the module that ships these
     * linters — the same exemption this class used to implement itself with an own-source marker
     * (this test lives in {@code src/main/java} and carries the forbidden tokens as string
     * literals, so it would otherwise report itself).
     */
    private static List<Path> findJavaSourceRoots() {
        return ReactorLayout.sourceRoots(JAVA_SUFFIX);
    }
}

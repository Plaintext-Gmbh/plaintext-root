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
 * Geteilter Quelltext-Guard gegen {@code jakarta.faces.view.ViewScoped} in ALLEN
 * {@code src/main/java} des jeweiligen Reactors.
 *
 * <p>Hintergrund: Die Backing-Beans wurden von {@code @ViewScoped} auf session-scoped umgestellt
 * ({@code @Component} + {@code @Scope("session")} + preRenderView-Listener {@code #{bean.onLoad()}} mit
 * {@code isPostback}-Guard). Diese Regel verhindert, dass die JSF-View-Scope-Annotation kuenftig wieder
 * eingefuehrt wird.
 *
 * <p><b>Warum ein Quelltext-Scan statt (nur) ArchUnit?</b> Der ArchUnit-Bytecode-Scan in
 * {@link PlaintextArchitectureTest} ({@code keineViewScopedBeans}) sieht nur Klassen auf dem
 * Classpath. Reactor-Module, die an keiner webapp haengen (z. B. {@code plaintext-admin-requirements}
 * in root), laegen ausserhalb der ArchUnit-Abdeckung. Dieser Scan laeuft ab der Reactor-Wurzel ueber
 * JEDES Modul-{@code src/main/java} und schliesst diese Luecke. Javadoc-/Kommentar-Erwaehnungen werden
 * bewusst NICHT als Verstoss gewertet.
 *
 * <p>Wie die anderen Klassen dieses Moduls liegt der Test in {@code src/main/java} von
 * {@code plaintext-root-archtests} und laeuft im Consumer via Surefire {@code <dependenciesToScan>}
 * gegen dessen Quelltext.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextViewScopedBanTest {

    private static final String JAVA_SUFFIX = "src/main/java";
    private static final String IMPORT_TOKEN = "import jakarta.faces.view.ViewScoped";
    private static final String FQ_ANNOTATION = "@jakarta.faces.view.ViewScoped";
    private static final String SHORT_ANNOTATION = "@ViewScoped";

    /**
     * Marker-Pfad der eigenen Linter-Quelle. Da dieser Test in {@code src/main/java} liegt, enthaelt
     * er die Verbots-Tokens (z. B. {@code "@ViewScoped"}) selbst als String-Literale/Test-Fixtures.
     * Der Quelltext-Scanner darf das Modul, das ihn ausliefert, deshalb NICHT scannen — sonst meldet
     * er sich selbst. In Consumern liegt der Linter als Jar vor (nicht in {@code src/main/java}), dort
     * greift diese Ausnahme nie und der komplette Consumer-Quelltext wird gescannt.
     */
    private static final String OWN_SOURCE_MARKER = "ch/plaintext/arch/PlaintextViewScopedBanTest.java";

    @Test
    void keineViewScopedNutzungInFrameworkQuelltext() throws IOException {
        List<Path> sourceRoots = findJavaSourceRoots();
        assertFalse(sourceRoots.isEmpty(),
                "Keine src/main/java-Verzeichnisse gefunden (cwd=" + Path.of("").toAbsolutePath() + ")");

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
     * Scannt jede {@code *.java}-Datei unter {@code root} und meldet echte ViewScoped-Annotation-Nutzung
     * (Import oder Annotation), aber KEINE Vorkommen in Zeilen-/Block-Kommentaren.
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
     * Findet ab dem Arbeitsverzeichnis nach oben die Reactor-Wurzel und sammelt jedes
     * {@code <modul>/src/main/java}. Faellt auf das eigene Modul zurueck, falls die Wurzel nicht gefunden wird.
     */
    private static List<Path> findJavaSourceRoots() throws IOException {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        List<Path> roots = new ArrayList<>();
        Path own = start.resolve(JAVA_SUFFIX);
        if (Files.isDirectory(own) && !shipsThisLinter(own)) {
            roots.add(own);
        }

        Path repoRoot = findRepoRoot(start);
        if (repoRoot != null) {
            try (Stream<Path> modules = Files.list(repoRoot)) {
                modules.filter(Files::isDirectory)
                       .map(m -> m.resolve(JAVA_SUFFIX))
                       .filter(Files::isDirectory)
                       .filter(p -> !roots.contains(p))
                       .filter(p -> !shipsThisLinter(p))
                       .forEach(roots::add);
            }
        }
        return roots;
    }

    /**
     * {@code true}, wenn {@code sourceRoot} das Modul ist, das diesen Linter ausliefert (enthaelt die
     * eigene Quelldatei mit den Verbots-Tokens). Solche Roots werden vom Scan ausgenommen, damit der
     * Linter sich nicht selbst meldet. Trifft nur in plaintext-root zu; in Consumern liegt der Linter
     * als Jar vor und kein Quell-Root enthaelt diese Datei.
     */
    private static boolean shipsThisLinter(Path sourceRoot) {
        return Files.isRegularFile(sourceRoot.resolve(OWN_SOURCE_MARKER));
    }

    /** Reactor-Wurzel = erstes Verzeichnis nach oben mit einer {@code pom.xml}, die {@code <modules>} enthaelt. */
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

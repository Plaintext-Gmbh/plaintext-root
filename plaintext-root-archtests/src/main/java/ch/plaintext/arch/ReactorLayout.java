/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pfadaufloesung der geteilten Datei-Linter: Wo ist die Reactor-Wurzel, welche Module gibt es,
 * welche {@code src/...}-Verzeichnisse sind zu scannen.
 *
 * <p>Die Tests dieses Moduls laufen an zwei Orten: in root selbst (Arbeitsverzeichnis =
 * {@code plaintext-root-webapp}) und in jedem Consumer, der das Jar per Surefire
 * {@code <dependenciesToScan>} zieht (Arbeitsverzeichnis = dessen webapp-Modul). In beiden Faellen
 * gilt: ab {@code user.dir} nach oben bis zur ersten {@code pom.xml} mit {@code <modules>} — das
 * ist der Reactor — und von dort in jedes (auch verschachtelte) Modul. Das Modul, das diese Linter
 * ausliefert, wird nie gescannt: seine Quellen tragen die Verbots-Tokens als Literale und
 * Test-Fixtures (in Consumern liegt es als Jar vor, dort greift die Ausnahme nie).
 *
 * <p>Bisher trug jede Linter-Klasse ihre eigene Kopie dieser Suche; die neuen Regeln des
 * Zustandsberichts vom 29.08.2026 teilen sie sich hier. Die aelteren Klassen bleiben unveraendert.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
final class ReactorLayout {

    /** Marker der eigenen Quelle: liegt sie unter {@code <modul>/src/main/java}, ist das Modul dieses hier. */
    private static final String OWN_SOURCE_MARKER = "ch/plaintext/arch/ReactorLayout.java";

    /** Verzeichnisse, in denen keine Modulquellen liegen — nicht betreten (Laufzeit + Fehlalarme). */
    private static final List<String> SKIP_DIRS = List.of("target", "src", ".git", "node_modules", ".mvn", ".idea");

    /** Maximale Modultiefe unter der Reactor-Wurzel. */
    private static final int MAX_MODULE_DEPTH = 5;

    private ReactorLayout() {
    }

    /** Arbeitsverzeichnis des Testlaufs (Surefire setzt {@code user.dir} auf das Modul). */
    static Path start() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    /** Reactor-Wurzel = erstes Verzeichnis nach oben, dessen {@code pom.xml} {@code <modules>} traegt — oder null. */
    static Path repoRoot() {
        return repoRoot(start());
    }

    static Path repoRoot(Path start) {
        Path dir = start;
        for (int i = 0; i < 8 && dir != null; i++) {
            Path pom = dir.resolve("pom.xml");
            try {
                if (Files.isRegularFile(pom) && Files.readString(pom).contains("<modules>")) {
                    return dir;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("pom.xml nicht lesbar: " + pom, e);
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Alle {@code <modul>/<suffix>}-Verzeichnisse des Reactors (eigenes Modul zuerst, dann jedes
     * — auch verschachtelte — Modul ab der Wurzel), ohne das Modul, das diese Linter ausliefert.
     *
     * @param suffix z. B. {@code src/main/resources/META-INF/resources} oder {@code src/main/java}
     */
    static List<Path> sourceRoots(String suffix) {
        Path start = start();
        List<Path> roots = new ArrayList<>();
        Path own = start.resolve(suffix);
        if (Files.isDirectory(own) && !shipsTheseLinters(start)) {
            roots.add(own);
        }
        Path repoRoot = repoRoot(start);
        if (repoRoot != null) {
            collect(repoRoot, suffix, roots, 0);
        }
        return roots;
    }

    static void collect(Path dir, String suffix, List<Path> roots, int depth) {
        if (depth > MAX_MODULE_DEPTH || shipsTheseLinters(dir)) {
            return;
        }
        Path candidate = dir.resolve(suffix);
        if (Files.isDirectory(candidate) && !roots.contains(candidate)) {
            roots.add(candidate);
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String name = child.getFileName().toString();
                if (!SKIP_DIRS.contains(name) && !name.startsWith(".")) {
                    collect(child, suffix, roots, depth + 1);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Modulverzeichnis nicht lesbar: " + dir, e);
        }
    }

    /** Pfad relativ zur Reactor-Wurzel (mit {@code /}), damit Meldungen und Allowlist-Eintraege ueberall gleich lauten. */
    static String relativ(Path file) {
        Path root = repoRoot();
        Path p = file.toAbsolutePath().normalize();
        String s = (root != null && p.startsWith(root)) ? root.relativize(p).toString() : p.toString();
        return s.replace('\\', '/');
    }

    static boolean shipsTheseLinters(Path moduleDir) {
        return Files.isRegularFile(moduleDir.resolve("src/main/java").resolve(OWN_SOURCE_MARKER));
    }
}

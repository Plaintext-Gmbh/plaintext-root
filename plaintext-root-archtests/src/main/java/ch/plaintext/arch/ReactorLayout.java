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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path resolution for the shared file linters: where the reactor root is, which modules exist,
 * which {@code src/...} directories have to be scanned.
 *
 * <p>The tests of this module run in two places: in root itself (working directory =
 * {@code plaintext-root-webapp}) and in every consumer that pulls the jar in via Surefire
 * {@code <dependenciesToScan>} (working directory = that consumer's webapp module). In both cases
 * the same rule applies: from {@code user.dir} upwards to the first {@code pom.xml} with
 * {@code <modules>} — that is the reactor — and from there into every (also nested) module. The
 * module that ships these linters is never scanned: its sources carry the forbidden tokens as
 * literals and test fixtures (in consumers it is present as a jar, where that exemption never
 * applies).
 *
 * <p>Until now every linter class carried its own copy of this lookup; the new rules from the
 * status report of 29.08.2026 share it here. The older classes remain unchanged.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class ReactorLayout {

    /** Marker for our own source: if it lies under {@code <modul>/src/main/java}, that module is this one. */
    private static final String OWN_SOURCE_MARKER = "ch/plaintext/arch/ReactorLayout.java";

    /** Directories that contain no module sources — do not descend into them (runtime + false alarms). */
    private static final List<String> SKIP_DIRS = List.of("target", "src", ".git", "node_modules", ".mvn", ".idea");

    /** Maximum module depth below the reactor root. */
    private static final int MAX_MODULE_DEPTH = 5;

    private ReactorLayout() {
    }

    /** Working directory of the test run (Surefire sets {@code user.dir} to the module). */
    static Path start() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    /** Reactor root = first directory upwards whose {@code pom.xml} carries {@code <modules>} — or null. */
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
     * All {@code <modul>/<suffix>} directories of the reactor (own module first, then every
     * — also nested — module below the root), without the module that ships these linters.
     *
     * @param suffix e.g. {@code src/main/resources/META-INF/resources} or {@code src/main/java}
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

    /** Path relative to the reactor root (with {@code /}), so that messages and allowlist entries read the same everywhere. */
    static String relativ(Path file) {
        Path root = repoRoot();
        Path p = file.toAbsolutePath().normalize();
        String s = (root != null && p.startsWith(root)) ? root.relativize(p).toString() : p.toString();
        return s.replace('\\', '/');
    }

    /**
     * Lower bound for the scan set of a file linter, to be called right after the lookup.
     *
     * <p>Every file linter here is a "nothing found" test: it is green when the scan produces no
     * violation — and just as green when the scan produces nothing at all. {@link #repoRoot()}
     * returns {@code null} as soon as no {@code pom.xml} carrying {@code <modules>} is found within
     * eight levels; {@link #sourceRoots(String)} then yields at most the linter's own module
     * directory, and the rule passes without having looked at the repository. Renaming, nesting or
     * moving a module is enough for that, and it happens silently. A negative control
     * ("an empty directory finds nothing") does not cover this — only a lower bound does.
     *
     * <p><b>The numbers the callers hand in are measured, not estimated.</b> Counted on 20.09.2026
     * over the six reactors that run this jar (root / app / guild / schuetu / iot / fwtool):
     * {@code src/main/resources} 23 / 29 / 7 / 2 / 2 / 5, {@code src/main/java} plus
     * {@code src/test/java} together 46 / 61 / 16 / 4 / 4 / 7,
     * {@code src/main/resources/META-INF/resources} 19 / 29 / 6 / 2 / 2 / 5. The bound is the
     * smallest of the six, because the same jar runs in all of them. A reactor that drops below it
     * has either really shrunk — then lower the constant deliberately and say why — or the lookup
     * no longer finds what is there, and that is the case this bound exists for.
     *
     * @param roots      what {@link #sourceRoots(String)} or an equivalent lookup returned
     * @param mindestens smallest number of roots measured across the six reactors
     * @param suffix     the scanned {@code src/...} suffix, for the message
     */
    static void untergrenze(List<Path> roots, int mindestens, String suffix) {
        Path repoRoot = repoRoot();
        assertNotNull(repoRoot,
                "Kein Reactor gefunden: von '" + start() + "' aufwaerts traegt binnen acht Ebenen keine "
                        + "pom.xml ein <modules>. Der Linter saehe damit hoechstens sein eigenes Modul und "
                        + "waere gruen, ohne das Repository gelesen zu haben.");
        assertTrue(roots.size() >= mindestens,
                () -> "Der Scan findet nur " + roots.size() + " '" + suffix + "'-Wurzeln im Reactor "
                        + repoRoot + ", festgehalten sind mindestens " + mindestens + ". Eine zu kleine "
                        + "Scanmenge sieht aus wie ein sauberes Repository und ist meist das Gegenteil: was "
                        + "der Linter nicht mehr findet, prueft er auch nicht mehr. Entweder ist der Reactor "
                        + "wirklich geschrumpft — dann die Zahl bewusst senken und begruenden — oder die "
                        + "Pfadsuche greift daneben. Gefunden: "
                        + roots.stream().map(ReactorLayout::relativ).sorted().collect(Collectors.joining(", ")));
    }

    /**
     * The {@code artifactId} of the reactor's root POM ({@code plaintext-root-parent},
     * {@code plaintext-parent}, {@code plaintext-guild-parent}, ...), or {@code null} if no reactor is
     * found. The {@code <parent>} block and comments are skipped, so the first remaining
     * {@code <artifactId>} is the POM's own.
     *
     * <p>Used by the guards lifted from the consumers (card 1298) to keep their <b>per-repository</b>
     * lower bounds: the copies carried one number each (root 40, schuetu 35, iot 2 ...). Taking the
     * smallest of the six for everybody would have been the weakest version winning — exactly what the
     * card forbids.
     */
    public static String reactorArtifactId() {
        Path root = repoRoot();
        if (root == null) {
            return null;
        }
        try {
            String pom = Files.readString(root.resolve("pom.xml"));
            pom = pom.replaceAll("(?s)<!--.*?-->", "");
            pom = pom.replaceFirst("(?s)<parent>.*?</parent>", "");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>").matcher(pom);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            throw new UncheckedIOException("pom.xml nicht lesbar: " + root, e);
        }
    }

    /**
     * Lower bound for this reactor: the measured value from {@code jeReactor} (key = root
     * {@code artifactId}), or {@code sonst} for a reactor that is not listed — the smallest of the
     * measured values, so that an unknown consumer is not red for a number measured elsewhere.
     */
    public static int mindestensFuerDiesenReactor(java.util.Map<String, Integer> jeReactor, int sonst) {
        String id = reactorArtifactId();
        return id != null && jeReactor.containsKey(id) ? jeReactor.get(id) : sonst;
    }

    static boolean shipsTheseLinters(Path moduleDir) {
        return Files.isRegularFile(moduleDir.resolve("src/main/java").resolve(OWN_SOURCE_MARKER));
    }
}

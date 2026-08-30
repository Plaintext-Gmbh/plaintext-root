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
final class ReactorLayout {

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

    static boolean shipsTheseLinters(Path moduleDir) {
        return Files.isRegularFile(moduleDir.resolve("src/main/java").resolve(OWN_SOURCE_MARKER));
    }
}

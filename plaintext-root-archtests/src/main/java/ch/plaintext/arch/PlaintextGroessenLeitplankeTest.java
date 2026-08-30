/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
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
 * Shared size guardrail (status report 29.08.2026, measure 12): no Java file over
 * {@value #MAX_ZEILEN} lines.
 *
 * <p><b>Why a line limit and not a complexity metric.</b> Lines are a crude measure — but an
 * incorruptible one, and it costs no second analyzer. The limit does not aim at "beautiful"
 * classes, but at the one effect that hurts in everyday work: a file beyond
 * {@value #MAX_ZEILEN} lines no longer fits into a review, inevitably collects several
 * responsibilities and turns into a merge-conflict magnet. The value is deliberately set high: it
 * is a ripcord against unbounded growth, not a style rule. Whoever reaches it has not written
 * "too long" — they have put at least two classes into one.
 *
 * <p><b>State on 30.08.2026.</b> root is green under this limit without a single exception (largest
 * file: {@code ClaudeAutomationServiceTest} with 1128 lines). Across the family exactly one file
 * currently breaks it: {@code plaintext-guild-events/…/EventService.java} (2027 lines). That root
 * is clean is the reason why the threshold may stand here instead of being postponed "for later" —
 * a guardrail that already needs exceptions when it is installed never becomes tight again.
 *
 * <p><b>Checked are {@code src/main/java} and {@code src/test/java}</b> of every module of the
 * reactor ({@link ReactorLayout}). Tests are explicitly included: a 2000-line test class is just
 * as unreadable as a 2000-line production class, and it grows faster.
 *
 * <p><b>Exceptions:</b> the reactor's allowlist ({@code plaintext-arch-allowlist.txt}, rule
 * {@value #ALLOWLIST_REGEL}, path relative to the reactor root, {@code *}/{@code **} allowed,
 * justification mandatory — {@link ArchAllowlist}). The threshold itself is deliberately <em>no</em>
 * property: an exception with a justification can be traced, a limit turned up cannot.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextGroessenLeitplankeTest {

    static final String ALLOWLIST_REGEL = "groesse-max-loc";

    /** Upper limit in lines per {@code .java} file. */
    static final int MAX_ZEILEN = 1500;

    private static final List<String> JAVA_SUFFIXES = List.of("src/main/java", "src/test/java");

    @Test
    @DisplayName("Keine Java-Datei ueber " + MAX_ZEILEN + " Zeilen")
    void keineUeberlangenJavaDateien() throws IOException {
        List<Path> roots = new ArrayList<>();
        for (String suffix : JAVA_SUFFIXES) {
            roots.addAll(ReactorLayout.sourceRoots(suffix));
        }
        if (roots.isEmpty()) {
            return; // reactor without Java sources at this point -> nothing to check
        }
        ArchAllowlist allowlist = ArchAllowlist.fuer(ALLOWLIST_REGEL);

        List<String> verstoesse = new ArrayList<>(allowlist.fehler());
        for (Path root : roots) {
            for (Treffer treffer : scan(root, MAX_ZEILEN)) {
                String rel = ReactorLayout.relativ(root.resolve(treffer.pfad()));
                if (!allowlist.erlaubt(rel)) {
                    verstoesse.add("%s -> %d Zeilen (Grenze %d)".formatted(rel, treffer.zeilen(), MAX_ZEILEN));
                }
            }
        }

        if (!verstoesse.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                    "\n\n=== GROESSEN-LEITPLANKE (Zustandsbericht 29.08.2026, Massnahme 12) ===\n");
            verstoesse.stream().sorted().forEach(v -> msg.append("  ! ").append(v).append("\n"));
            msg.append("\nEine Datei ueber ").append(MAX_ZEILEN).append(" Zeilen traegt mehr als eine Zustaendigkeit.\n")
               .append("Herausloesen statt kuerzen: der zweite Verantwortungsbereich wird eine eigene Klasse.\n")
               .append("Begruendete Ausnahme: '").append(ALLOWLIST_REGEL).append(" <pfad>  # <Grund>' in ")
               .append(ArchAllowlist.DATEINAME).append(" — die Schwelle bleibt, wo sie ist.\n");
            fail(msg.toString());
        }
    }

    @Test
    @DisplayName("Scanner zaehlt Zeilen und meldet nur ueber der Grenze")
    void scannerMeldetNurUeberDerGrenze(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("ch/plaintext/x"));
        Files.writeString(pkg.resolve("Klein.java"), "a\nb\nc\n");
        Files.writeString(pkg.resolve("Genau.java"), "x\n".repeat(5));
        Files.writeString(pkg.resolve("Gross.java"), "x\n".repeat(6));
        Files.writeString(pkg.resolve("KeinJava.txt"), "x\n".repeat(99));

        List<Treffer> treffer = scan(tmp, 5);

        assertEquals(1, treffer.size(), () -> "Nur die Datei ueber der Grenze zaehlt: " + treffer);
        assertEquals("ch/plaintext/x/Gross.java", treffer.getFirst().pfad());
        assertEquals(6, treffer.getFirst().zeilen());
        assertTrue(scan(Path.of("gibt/es/nicht"), 5).isEmpty());
        assertTrue(scan(null, 5).isEmpty());
    }

    /** An overlong file: path relative to {@code root} and its line count. */
    record Treffer(String pfad, long zeilen) {
    }

    /** Reports every {@code .java} file below {@code root} with more than {@code grenze} lines. */
    static List<Treffer> scan(Path root, int grenze) throws IOException {
        List<Treffer> treffer = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return treffer;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                                  .filter(p -> p.getFileName().toString().endsWith(".java"))
                                  .sorted().toList()) {
                long zeilen;
                try (Stream<String> lines = Files.lines(file)) {
                    zeilen = lines.count();
                }
                if (zeilen > grenze) {
                    treffer.add(new Treffer(root.relativize(file).toString().replace('\\', '/'), zeilen));
                }
            }
        }
        return treffer;
    }
}

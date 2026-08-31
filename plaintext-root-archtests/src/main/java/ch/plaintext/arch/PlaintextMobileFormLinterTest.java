/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import ch.plaintext.boot.utils.MobileFormLinter;
import ch.plaintext.boot.utils.MobileFormLinter.Violation;
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
 * Shared linter guard against mobile anti-patterns in ALL framework XHTML.
 *
 * <p>Trigger: {@code <p:dialog width="560">} (fixed px width) runs out of the viewport on the right
 * on a phone. The central {@code mobile-responsive.css} (plaintext-root-template) caps every dialog
 * at {@code 96vw}; this test additionally prevents NEW fixed-width dialogs from being added
 * unnoticed — they must either be switched to {@code styleClass="mobile-safe"} or be exempted with a
 * justification ({@code styleClass="mobile-exempt"} resp. {@code <!-- mobile-ok -->}).
 *
 * <p>The actual scan code lives in {@link MobileFormLinter} (in plaintext-root-common). This
 * test lives in {@code src/main/java} of the shared module {@code plaintext-root-archtests}; consumers
 * (app, iot, fwtool, schuetu) take the module as a test dependency and let the test run via
 * Surefire {@code <dependenciesToScan>} — it scans, starting from the working directory (consumer
 * reactor root), every {@code src/main/resources/META-INF/resources} and reports violations. No more
 * copy-paste needed.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextMobileFormLinterTest {

    private static final String RESOURCES_SUFFIX = "src/main/resources/META-INF/resources";

    /**
     * Scans every {@code src/main/resources/META-INF/resources} of all reactor modules (from the
     * repository root) and fails with file + line on every mobile anti-pattern.
     * Consumer apps without XHTML views of their own (no META-INF/resources directories) have
     * nothing to lint — the test then passes instead of failing.
     */
    @Test
    void keineMobileAntiPatternsInFrameworkXhtml() throws IOException {
        List<Path> resourceRoots = findResourceRoots();
        if (resourceRoots.isEmpty()) {
            // No XHTML in the reactor (e.g. a consumer without views of its own) -> nothing to check.
            return;
        }

        List<Violation> violations = new ArrayList<>();
        for (Path root : resourceRoots) {
            violations.addAll(MobileFormLinter.scan(root));
        }

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                    "\n\n=== MOBILE-ANTI-PATTERNS in Framework-XHTML (Handy-Viewport-Ueberlauf) ===\n");
            for (Violation v : violations) {
                msg.append("  ! ").append(v).append("\n");
            }
            msg.append("\nFix: fixe width am p:dialog entfernen und styleClass=\"mobile-safe\" setzen,\n")
               .append("oder begruendet ausnehmen (styleClass=\"mobile-exempt\" bzw. <!-- mobile-ok -->).\n");
            fail(msg.toString());
        }
    }

    @Test
    void linterErkenntFixeBreiteUndRespektiertOptOut(@TempDir Path tmp) throws IOException {
        Path res = Files.createDirectories(tmp.resolve("META-INF/resources"));

        Files.writeString(res.resolve("bad.xhtml"),
                "<p:dialog header=\"X\" modal=\"true\" width=\"560\"></p:dialog>");
        Files.writeString(res.resolve("badpx.xhtml"),
                "<p:dialog header=\"X\"\n          width=\"700px\"></p:dialog>");
        Files.writeString(res.resolve("okPercent.xhtml"),
                "<p:dialog header=\"X\" width=\"100%\"></p:dialog>");
        Files.writeString(res.resolve("noWidthNoSafe.xhtml"),
                "<p:dialog header=\"X\" modal=\"true\"></p:dialog>"); // no width, no mobile-safe -> violation
        Files.writeString(res.resolve("okNoWidth.xhtml"),
                "<p:dialog header=\"X\" styleClass=\"mobile-safe\"></p:dialog>");
        Files.writeString(res.resolve("exemptClass.xhtml"),
                "<p:dialog header=\"X\" styleClass=\"mobile-exempt\" width=\"560\"></p:dialog>");
        Files.writeString(res.resolve("exemptComment.xhtml"),
                "<p:dialog header=\"X\" width=\"560\"></p:dialog> <!-- mobile-ok -->");

        List<Violation> violations = MobileFormLinter.scan(res);

        // bad.xhtml + badpx.xhtml (fixed px) and noWidthNoSafe.xhtml (no width, no mobile-safe) are violations;
        // okPercent (width="100%"), okNoWidth (mobile-safe), exemptClass/exemptComment are fine.
        assertEquals(3, violations.size(),
                "Erwartet genau 3 Verstoesse, gefunden: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.file().getFileName().toString().equals("bad.xhtml")));
        assertTrue(violations.stream().anyMatch(v -> v.file().getFileName().toString().equals("badpx.xhtml")));
        assertTrue(violations.stream().anyMatch(v -> v.file().getFileName().toString().equals("noWidthNoSafe.xhtml")));
    }

    @Test
    void scanAufNichtVorhandenemPfadLiefertLeereListe() {
        assertTrue(MobileFormLinter.scan(Path.of("does/not/exist/xyz")).isEmpty());
        assertTrue(MobileFormLinter.scan(null).isEmpty());
    }

    /**
     * Walks upwards from the working directory to the repository root and collects every
     * {@code <modul>/src/main/resources/META-INF/resources}. Falls back to our own module
     * if the root is not found (e.g. an isolated module build).
     */
    private static List<Path> findResourceRoots() throws IOException {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        // Own module resources first (guaranteed to be present, no matter where the build runs from).
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

    /** Repository root = first directory upwards that holds a Maven reactor (pom.xml with &lt;modules&gt;). */
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

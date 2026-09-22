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
     * Lower bound for the scan set (measured, see {@link ReactorLayout#untergrenze}). Smallest of
     * the six reactors on 20.09.2026 — schuetu and iot with two roots each.
     */
    private static final int MINDESTENS_SCANWURZELN = 2;

    /**
     * Scans every {@code src/main/resources/META-INF/resources} of all reactor modules (from the
     * repository root) and fails with file + line on every mobile anti-pattern. The scan set
     * carries a lower bound ({@link ReactorLayout#untergrenze}): a linter that finds nothing to
     * scan reports "everything in order" and is the worst possible state with a green result.
     */
    @Test
    void keineMobileAntiPatternsInFrameworkXhtml() throws IOException {
        List<Path> resourceRoots = findResourceRoots();
        ReactorLayout.untergrenze(resourceRoots, MINDESTENS_SCANWURZELN, RESOURCES_SUFFIX);

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
     * All {@code <modul>/src/main/resources/META-INF/resources} of the reactor — from
     * {@link ReactorLayout#sourceRoots}, no longer an own copy.
     *
     * <p><b>Karte 1294, 22.09.2026:</b> until then this class carried a <b>flat</b>
     * {@code Files.list(repoRoot)} plus its own {@code findRepoRoot}. Flat versus recursive is
     * not a matter of taste: a NESTED module ({@code gruppe/modul/src/main/...}) was invisible to
     * the flat search, and the linter was then green because it did not know the directory — not
     * because the directory was clean. Measured on 20.09.2026 both searches returned the same
     * number in all six repos; that is an accident of today's flat module layout, not
     * equivalence. The lower bound from card 1274 catches an empty scan set, not a scan set that
     * is complete-looking but short by one nested module — only {@link ReactorLayout} does that.
     */
    private static List<Path> findResourceRoots() {
        return ReactorLayout.sourceRoots(RESOURCES_SUFFIX);
    }
}

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
 * Geteilter Linter-Guard gegen Mobile-Anti-Patterns in ALLEN Framework-XHTML.
 *
 * <p>Ausloeser: {@code <p:dialog width="560">} (fixe px-Breite) laeuft auf dem Handy rechts aus dem
 * Viewport. Das zentrale {@code mobile-responsive.css} (plaintext-root-template) deckelt jeden Dialog
 * auf {@code 96vw}; dieser Test verhindert zusaetzlich, dass NEUE fixe-Breite-Dialoge unbemerkt
 * hinzukommen — sie muessen entweder auf {@code styleClass="mobile-safe"} umgestellt oder begruendet
 * ausgenommen werden ({@code styleClass="mobile-exempt"} bzw. {@code <!-- mobile-ok -->}).
 *
 * <p>Der eigentliche Scan-Code lebt in {@link MobileFormLinter} (in plaintext-root-common). Dieser
 * Test liegt in {@code src/main/java} des geteilten Moduls {@code plaintext-root-archtests}; Consumer
 * (app, iot, fwtool, schuetu) nehmen das Modul als Test-Dependency auf und lassen den Test via
 * Surefire {@code <dependenciesToScan>} laufen — er scannt ab dem Arbeitsverzeichnis (Consumer-
 * Reactor-Wurzel) jedes {@code src/main/resources/META-INF/resources} und meldet Verstoesse. Kein
 * Copy-Paste mehr noetig.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextMobileFormLinterTest {

    private static final String RESOURCES_SUFFIX = "src/main/resources/META-INF/resources";

    /**
     * Scannt jedes {@code src/main/resources/META-INF/resources} aller Reactor-Module (ab Repo-Wurzel)
     * und schlaegt bei jedem Mobile-Anti-Pattern mit Datei + Zeile fehl.
     * Consumer-Apps ohne eigene XHTML-Views (keine META-INF/resources-Verzeichnisse) haben
     * nichts zu linten — der Test besteht dann, statt zu scheitern.
     */
    @Test
    void keineMobileAntiPatternsInFrameworkXhtml() throws IOException {
        List<Path> resourceRoots = findResourceRoots();
        if (resourceRoots.isEmpty()) {
            // Kein XHTML im Reactor (z.B. Consumer ohne eigene Views) -> nichts zu pruefen.
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
                "<p:dialog header=\"X\" modal=\"true\"></p:dialog>"); // keine width, kein mobile-safe -> Verstoss
        Files.writeString(res.resolve("okNoWidth.xhtml"),
                "<p:dialog header=\"X\" styleClass=\"mobile-safe\"></p:dialog>");
        Files.writeString(res.resolve("exemptClass.xhtml"),
                "<p:dialog header=\"X\" styleClass=\"mobile-exempt\" width=\"560\"></p:dialog>");
        Files.writeString(res.resolve("exemptComment.xhtml"),
                "<p:dialog header=\"X\" width=\"560\"></p:dialog> <!-- mobile-ok -->");

        List<Violation> violations = MobileFormLinter.scan(res);

        // bad.xhtml + badpx.xhtml (fixe px) und noWidthNoSafe.xhtml (keine width, kein mobile-safe) sind Verstoesse;
        // okPercent (width="100%"), okNoWidth (mobile-safe), exemptClass/exemptComment sind ok.
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
     * Findet ab dem Arbeitsverzeichnis nach oben die Repo-Wurzel und sammelt jedes
     * {@code <modul>/src/main/resources/META-INF/resources}. Faellt auf das eigene Modul zurueck,
     * falls die Wurzel nicht gefunden wird (z. B. isolierter Modul-Build).
     */
    private static List<Path> findResourceRoots() throws IOException {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        // Eigene Modul-Ressourcen zuerst (garantiert vorhanden, egal von wo gebaut wird).
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

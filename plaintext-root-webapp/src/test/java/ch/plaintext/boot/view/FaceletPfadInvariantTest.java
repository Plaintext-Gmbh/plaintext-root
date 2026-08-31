/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.view;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Facelet-Verweise muessen context-relativ sein, also mit einem {@code /} beginnen.
 *
 * <p><b>Warum das ein Build-Fehler ist und keine Stilfrage</b> (30.08.2026, Renovate-PRs #153/#154):
 * Mojarra hat in <b>4.1.13</b> mit
 * <a href="https://github.com/eclipse-ee4j/mojarra/pull/5908">PR #5908</a> CVE-2026-46581
 * geschlossen — Pfad-Traversal und Remote-Einbindung ueber {@code ui:include} (CVSS 7.5, in
 * Red Hats Lesart unauthentifizierte RCE). Seither laeuft ein Verweis OHNE fuehrenden Slash in
 * {@code DefaultFaceletFactory.resolveURL} durch drei neue Schranken:
 *
 * <pre>
 * if (path.startsWith("/")) {
 *     return resolver.resolveUrl(path);      // context-relativ, ungeprueft
 * }
 * URL url = new URL(source, path);           // relativ zur URL des LAUFENDEN Facelets
 * requireSameOrigin(source, url, path);
 * requireWithinApplicationRoot(url, path);   // &lt;- hier bricht es
 * requireFaceletResource(url, path);
 * </pre>
 *
 * <p>{@code requireWithinApplicationRoot} verlangt, dass die aufgeloeste URL unterhalb von
 * {@code resolver.resolveUrl("/")} liegt. In dieser Projektfamilie liegen die Facelets aber nicht
 * im Servlet-Document-Root, sondern als {@code META-INF/resources} in JARs auf dem Classpath. Ein
 * relativer Verweis loest damit ins JAR der <i>aufrufenden</i> Seite auf, der Anwendungs-Root zeigt
 * woandershin — gleiches Protokoll, anderer Praefix, {@code FacesFileNotFoundException}. Die Seite
 * liefert 500, und weil das Layout mit dem Template stirbt, verschwinden Menue und Topbar
 * gleich mit. Genau so sind am 30.08.2026 drei Tests in {@code RootPagesPlaywrightIT} gefallen,
 * ohne dass am Menue selbst etwas geaendert worden waere.
 *
 * <p><b>Kein Zurueckdrehen.</b> Ein Pin auf &le; 4.1.12 wuerde CVE-2026-46581 wieder aufreissen.
 * Ein Abschalter fuer die Pruefung existiert nicht. Der fuehrende Slash ist der Weg.
 *
 * <p>Vorbild fuer den reinen Quellcode-Scan ueber alle Module ohne Spring-Kontext:
 * {@code MenuLinkInvariantTest}.
 */
class FaceletPfadInvariantTest {

    /** Modul, an dem das Repo-Root erkannt wird. */
    private static final String ANKER_MODUL = "plaintext-root-webapp";

    /** {@code template="..."} an {@code ui:composition} / {@code ui:decorate}. */
    private static final Pattern TEMPLATE_ATTRIBUT =
            Pattern.compile("\\btemplate\\s*=\\s*\"([^\"]*)\"");

    /** {@code src="..."} an {@code ui:include}. */
    private static final Pattern UI_INCLUDE =
            Pattern.compile("<ui:include\\b[^>]*?\\bsrc\\s*=\\s*\"([^\"]*)\"", Pattern.DOTALL);

    @Test
    void jederTemplateVerweisIstContextRelativ() throws IOException {
        pruefe(TEMPLATE_ATTRIBUT, "template");
    }

    @Test
    void jederUiIncludeVerweisIstContextRelativ() throws IOException {
        pruefe(UI_INCLUDE, "ui:include src");
    }

    private void pruefe(Pattern muster, String bezeichnung) throws IOException {
        Path repoRoot = findeRepoRoot();
        List<Path> facelets = sammleFacelets(repoRoot);
        assertFalse(facelets.isEmpty(), "Keine .xhtml gefunden — Pfadaufloesung pruefen");

        List<String> verstoesse = new ArrayList<>();
        for (Path datei : facelets) {
            String inhalt = Files.readString(datei);
            Matcher m = muster.matcher(inhalt);
            while (m.find()) {
                String pfad = m.group(1).trim();
                if (pfad.isEmpty() || pfad.startsWith("/") || pfad.startsWith("#{")) {
                    // Leer, context-relativ oder per EL berechnet — hier nicht statisch pruefbar.
                    continue;
                }
                verstoesse.add(repoRoot.relativize(datei) + ": " + bezeichnung + "=\"" + pfad + "\"");
            }
        }

        if (!verstoesse.isEmpty()) {
            fail("Facelet-Verweis ohne fuehrenden \"/\". Seit Mojarra 4.1.13 (CVE-2026-46581, "
                    + "PR #5908) wird ein relativer Verweis gegen die URL des laufenden Facelets "
                    + "aufgeloest und muss unterhalb des Anwendungs-Roots liegen. Unsere Facelets "
                    + "liegen in META-INF/resources auf dem Classpath, also NICHT dort — die Seite "
                    + "liefert 500 und Menue plus Topbar verschwinden mit dem Layout.\n"
                    + "Bitte den fuehrenden \"/\" ergaenzen (context-relativ):\n  "
                    + String.join("\n  ", verstoesse));
        }
    }

    /** Alle ausgelieferten Facelets aller Module — {@code target/} bleibt aussen vor. */
    private List<Path> sammleFacelets(Path repoRoot) throws IOException {
        try (Stream<Path> pfade = Files.walk(repoRoot)) {
            return pfade
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".xhtml"))
                    .filter(p -> !p.toString().contains(java.io.File.separator + "target" + java.io.File.separator))
                    .sorted()
                    .toList();
        }
    }

    /** Repo-Root ueber das Ankermodul finden (Test laeuft mit wechselndem working directory). */
    private Path findeRepoRoot() {
        Path kandidat = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && kandidat != null; i++) {
            if (Files.isDirectory(kandidat.resolve(ANKER_MODUL))) {
                return kandidat;
            }
            kandidat = kandidat.getParent();
        }
        throw new IllegalStateException("Repo-Root (Verzeichnis mit " + ANKER_MODUL + ") nicht gefunden");
    }
}

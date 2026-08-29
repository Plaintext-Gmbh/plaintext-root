/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import ch.plaintext.boot.utils.FaceletsElLinter;
import ch.plaintext.boot.utils.FaceletsElLinter.Violation;
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
 * Geteilter Linter-Guard gegen den Facelets-EL-Fallstrick in ALLEN {@code src/main/resources/**}
 * {@code .xhtml} des jeweiligen Reactors.
 *
 * <p>Ausloeser (real in {@code setup.xhtml} aufgetreten): ein gerades {@code "} in einem inline-EL
 * im Body-Text — z. B. {@code #{i18n.t('... „Global – System" ...')}} — sprengt den Facelets-
 * Textparser ({@code ELText.findVarLength}) mit "EL Expression Unbalanced" und die komplette Seite
 * endet in einem 500 / Whitelabel-Error. Der Test verhindert, dass so etwas erneut unbemerkt
 * hinzukommt.
 *
 * <p><b>Zustandsbericht 29.08.2026, Paket R2:</b> Dieser Test lag bisher als lokale Kopie in
 * {@code plaintext-root-webapp/src/test} (und in guild/schuetu nochmals). Jetzt liegt er wie die
 * uebrigen Regeln in {@code src/main/java} von {@code plaintext-root-archtests} und laeuft im
 * Consumer via Surefire {@code <dependenciesToScan>} ab dessen Reactor-Wurzel ueber jedes
 * Modul-{@code src/main/resources} ({@link ReactorLayout}). Consumer ohne eigene XHTML bestehen.
 *
 * <p><b>Ausnahmen:</b> {@code <!-- el-quote-ok -->} in derselben Zeile (praktisch nie noetig — es
 * ist immer ein echter Fehler); ganze Dateien ueber die Allowlist des Reactors
 * ({@code plaintext-arch-allowlist.txt}, Regel {@code facelets-el}, siehe {@link ArchAllowlist}).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextFaceletsElLinterTest {

    static final String ALLOWLIST_REGEL = "facelets-el";

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * Scannt jedes {@code src/main/resources} aller Reactor-Module und schlaegt bei jedem geraden
     * {@code "} in einem inline-Body-EL mit Datei + Zeile fehl.
     */
    @Test
    void keinGeradesQuoteInInlineElVonXhtml() {
        List<Path> resourceRoots = ReactorLayout.sourceRoots(RESOURCES_SUFFIX);
        if (resourceRoots.isEmpty()) {
            return;
        }
        ArchAllowlist allowlist = ArchAllowlist.fuer(ALLOWLIST_REGEL);

        List<String> violations = new ArrayList<>(allowlist.fehler());
        for (Path root : resourceRoots) {
            for (Violation v : FaceletsElLinter.scan(root)) {
                String rel = ReactorLayout.relativ(v.file());
                if (!allowlist.erlaubt(rel)) {
                    violations.add(rel + ":" + v.line() + " -> " + v.message());
                }
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder("""
                    \n
                    === FACELETS-EL-FALLSTRICK: gerades " in inline-#{...} im Body-Text ===
                    (bricht ELText.findVarLength -> 500/Whitelabel auf der ganzen Seite)
                    """);
            violations.forEach(v -> msg.append("  ! ").append(v).append("\n"));
            msg.append("\nFix: im EL-String deutsche Typografie-Quotes („ “) statt geradem \" verwenden.\n")
               .append("Begruendete Ausnahme: <!-- el-quote-ok --> in derselben Zeile oder Eintrag '")
               .append(ALLOWLIST_REGEL).append(" <pfad>  # <Grund>' in ").append(ArchAllowlist.DATEINAME).append(".\n");
            fail(msg.toString());
        }
    }

    @Test
    void linterErkenntGeradesQuoteUndRespektiertKontextUndOptOut(@TempDir Path tmp) throws IOException {
        Path res = Files.createDirectories(tmp.resolve("META-INF/resources"));

        // ECHTE Verstoesse: gerades " in inline-Body-EL.
        Files.writeString(res.resolve("bad.xhtml"),
                "<small>#{i18n.t('Sichtbarkeit „Global – System\" in der Mailbox')}</small>");
        Files.writeString(res.resolve("badMultiline.xhtml"),
                "<h:panelGroup>\n    #{i18n.t('Lege ein Konto mit Sichtbarkeit „X\" an')}\n</h:panelGroup>");

        // KEINE Verstoesse:
        // (a) EL im Attributwert – dort ist " der Attribut-Delimiter, harmlos und ueberall ueblich.
        Files.writeString(res.resolve("okAttr.xhtml"),
                "<p:confirm header=\"#{i18n.t('Bestätigung')}\" message=\"#{i18n.t('Wirklich löschen?')}\"/>");
        // (b) Body-EL mit korrekten deutschen Typografie-Quotes.
        Files.writeString(res.resolve("okTypografie.xhtml"),
                "<small>#{i18n.t('Sichtbarkeit „Global – System“ in der Mailbox')}</small>");
        // (c) Gerades " im normalen Body-Text ausserhalb eines EL-Ausdrucks.
        Files.writeString(res.resolve("okPlainText.xhtml"),
                "<small>Sichtbarkeit \"Global\" ist ok #{bean.wert}</small>");
        // (d) Begruendetes Opt-out in derselben Zeile.
        Files.writeString(res.resolve("okOptOut.xhtml"),
                "<small>#{i18n.t('Rest \" Rest')}</small> <!-- el-quote-ok -->");

        List<Violation> violations = FaceletsElLinter.scan(res);

        assertEquals(2, violations.size(),
                "Erwartet genau 2 Verstoesse (bad + badMultiline), gefunden: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.file().getFileName().toString().equals("bad.xhtml")));
        assertTrue(violations.stream().anyMatch(v -> v.file().getFileName().toString().equals("badMultiline.xhtml")));
    }

    @Test
    void scanAufNichtVorhandenemPfadLiefertLeereListe() {
        assertTrue(FaceletsElLinter.scan(Path.of("does/not/exist/xyz")).isEmpty());
        assertTrue(FaceletsElLinter.scan(null).isEmpty());
    }
}

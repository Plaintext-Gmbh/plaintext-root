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
 * Shared linter guard against the Facelets EL pitfall in ALL {@code src/main/resources/**}
 * {@code .xhtml} of the respective reactor.
 *
 * <p>Trigger (which really happened in {@code setup.xhtml}): a straight {@code "} inside an inline EL
 * in body text — e.g. {@code #{i18n.t('... „Global – System" ...')}} — blows up the Facelets text
 * parser ({@code ELText.findVarLength}) with "EL Expression Unbalanced" and the complete page ends
 * in a 500 / whitelabel error. The test prevents such a thing from being added again unnoticed.
 *
 * <p><b>Status report 29.08.2026, package R2:</b> this test used to be a local copy in
 * {@code plaintext-root-webapp/src/test} (and once more in guild/schuetu). Now, like the other
 * rules, it lives in {@code src/main/java} of {@code plaintext-root-archtests} and runs in the
 * consumer via Surefire {@code <dependenciesToScan>} from that consumer's reactor root over every
 * module's {@code src/main/resources} ({@link ReactorLayout}). Consumers without XHTML of their own pass.
 *
 * <p><b>Exceptions:</b> {@code <!-- el-quote-ok -->} on the same line (practically never needed — it
 * is always a genuine defect); whole files via the reactor's allowlist
 * ({@code plaintext-arch-allowlist.txt}, rule {@code facelets-el}, see {@link ArchAllowlist}).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextFaceletsElLinterTest {

    static final String ALLOWLIST_REGEL = "facelets-el";

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * Scans every {@code src/main/resources} of all reactor modules and fails with file + line on
     * every straight {@code "} inside an inline body EL.
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

        // REAL violations: straight " inside an inline body EL.
        Files.writeString(res.resolve("bad.xhtml"),
                "<small>#{i18n.t('Sichtbarkeit „Global – System\" in der Mailbox')}</small>");
        Files.writeString(res.resolve("badMultiline.xhtml"),
                "<h:panelGroup>\n    #{i18n.t('Lege ein Konto mit Sichtbarkeit „X\" an')}\n</h:panelGroup>");

        // NO violations:
        // (a) EL in an attribute value - there " is the attribute delimiter, harmless and common everywhere.
        Files.writeString(res.resolve("okAttr.xhtml"),
                "<p:confirm header=\"#{i18n.t('Bestätigung')}\" message=\"#{i18n.t('Wirklich löschen?')}\"/>");
        // (b) Body EL with correct German typographic quotes.
        Files.writeString(res.resolve("okTypografie.xhtml"),
                "<small>#{i18n.t('Sichtbarkeit „Global – System“ in der Mailbox')}</small>");
        // (c) Straight " in ordinary body text outside an EL expression.
        Files.writeString(res.resolve("okPlainText.xhtml"),
                "<small>Sichtbarkeit \"Global\" ist ok #{bean.wert}</small>");
        // (d) Justified opt-out on the same line.
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

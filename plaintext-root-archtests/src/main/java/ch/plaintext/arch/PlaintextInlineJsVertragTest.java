/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import ch.plaintext.boot.utils.InlineJsLinter;
import ch.plaintext.boot.utils.InlineJsLinter.Violation;
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
 * Shared contract test against inline JavaScript in ALL {@code src/main/resources/**}
 * {@code .xhtml} of the respective reactor — the precondition for the content security policy to
 * work without {@code script-src 'unsafe-inline'} (wave 4).
 *
 * <p><b>Why this is needed.</b> {@code PlaintextSecurityConfig} sets a detailed CSP, but
 * {@code script-src} carries {@code 'unsafe-inline'}. With that, every injected {@code <script>}
 * runs: at this point the policy prevents nothing. It can only be tightened once NO inline
 * JavaScript of our own is left in the markup — a single remaining block anywhere in the reactor is
 * enough to make the switch unthrowable. And because new inline JavaScript never draws attention
 * while it is being written (the page does work, after all), this needs a test and not a convention.
 *
 * <p><b>Two rules</b> (scan logic in {@link InlineJsLinter}, plaintext-root-common):
 * <ol>
 *   <li>{@code <script>} resp. {@code <h:outputScript>} WITH a body instead of an external file.</li>
 *   <li>An {@code on…} attribute on a non-{@code p:} tag ({@code onclick} on {@code <a>},
 *       {@code onchange} on {@code <select>} …). {@code on…} on {@code p:} components stays
 *       unobjected: {@code oncomplete}/{@code onstart}/{@code onerror} are PrimeFaces events of the
 *       Ajax lifecycle and no HTML attributes at all, and where PrimeFaces really does generate an
 *       HTML handler ({@code onclick} on {@code p:commandButton}), the CSP mode
 *       {@code joinfaces.primefaces.csp=true} extracts it itself and registers it via
 *       {@code PrimeFaces.csp}.</li>
 * </ol>
 *
 * <p><b>Enforcing or only reporting — {@value #SCHALTER}.</b> The test runs in two positions:
 * <ul>
 *   <li>{@code enforce}: every violation makes the run fail. That is how it is set in
 *       {@code plaintext-root-webapp/pom.xml} (Surefire {@code systemPropertyVariables}) — root has
 *       been clean since wave 4 and stays that way.</li>
 *   <li>{@code report} (default): the findings are printed to {@code System.err}, the test passes.
 *       That is the position for every consumer (app, guild, iot, schuetu) that has not yet cleaned
 *       up its legacy code — a root release must not topple a build there while the cleanup is still
 *       under way. Once a consumer is through, it sets the same two lines as root in its webapp
 *       {@code pom.xml} and is protected from then on.</li>
 * </ul>
 * The default is deliberately the weaker one: a rule that breaks foreign builds when it arrives gets
 * switched off instead of followed.
 *
 * <p><b>Exceptions:</b> {@code <!-- inline-js-ok -->} on the same line exempts a single hit; whole
 * files are exempted by the reactor's allowlist
 * ({@code plaintext-arch-allowlist.txt}, rule {@code inline-js}, justification mandatory — see
 * {@link ArchAllowlist}). root keeps no allowlist.
 *
 * <p>This test lives in {@code src/main/java} of {@code plaintext-root-archtests} and runs in the
 * consumer via Surefire {@code <dependenciesToScan>} from that consumer's reactor root over every
 * module's {@code src/main/resources} (path resolution: {@link ReactorLayout}) — never over the
 * resources of root, which are present as a jar in the consumer.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextInlineJsVertragTest {

    static final String ALLOWLIST_REGEL = "inline-js";

    /** System property that decides between enforcing ({@code enforce}) and only reporting ({@code report}). */
    static final String SCHALTER = "plaintext.arch.inline-js";

    static final String SCHARF = "enforce";

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * Scans every {@code src/main/resources} of all reactor modules. Consumers without XHTML of their
     * own have nothing to lint — the test then passes.
     */
    @Test
    void keinInlineJavascriptInXhtml() {
        List<Path> resourceRoots = ReactorLayout.sourceRoots(RESOURCES_SUFFIX);
        if (resourceRoots.isEmpty()) {
            return;
        }
        ArchAllowlist allowlist = ArchAllowlist.fuer(ALLOWLIST_REGEL);

        List<String> violations = new ArrayList<>(allowlist.fehler());
        for (Path root : resourceRoots) {
            for (Violation v : InlineJsLinter.scan(root)) {
                String rel = ReactorLayout.relativ(v.file());
                if (!allowlist.erlaubt(rel)) {
                    violations.add(rel + ":" + v.line() + " [" + v.rule() + "] -> " + v.message());
                }
            }
        }
        if (violations.isEmpty()) {
            return;
        }

        StringBuilder msg = new StringBuilder("\n\n=== INLINE-JAVASCRIPT (Welle 4: CSP ohne 'unsafe-inline') ===\n");
        violations.forEach(v -> msg.append("  ! ").append(v).append("\n"));
        msg.append("\nSolange auch nur ein Inline-Block existiert, muss script-src 'unsafe-inline' fuehren —\n")
           .append("dann laeuft auch jedes eingeschleuste <script>, und die CSP schuetzt an dieser Stelle nicht.\n")
           .append("Fix: Rumpf nach src/main/resources/META-INF/resources/<library>/js/ auslagern und per\n")
           .append("<h:outputScript library=\"…\" name=\"js/….js\"/> einbinden; on…-Attribute durch\n")
           .append("addEventListener ersetzen, angebunden ueber eine ID oder ein data-Attribut.\n")
           .append("Begruendete Ausnahme: <!-- ").append(InlineJsLinter.EXEMPT_COMMENT).append(" --> in derselben\n")
           .append("Zeile oder Eintrag '").append(ALLOWLIST_REGEL).append(" <pfad>  # <Grund>' in ")
           .append(ArchAllowlist.DATEINAME).append(".\n");

        if (scharf()) {
            fail(msg.toString());
        }
        // Reporting: print and let it pass. Switch to enforcing with
        // -Dplaintext.arch.inline-js=enforce resp. the two Surefire lines from root.
        System.err.println(msg + "\nNUR GEMELDET (" + SCHALTER + " != " + SCHARF + "). Nach der Bereinigung\n"
                + "in <systemPropertyVariables> des webapp-Moduls " + SCHALTER + "=" + SCHARF + " setzen.\n");
    }

    static boolean scharf() {
        return SCHARF.equalsIgnoreCase(System.getProperty(SCHALTER, "report"));
    }

    @Test
    void linterErkenntBeideRegelnUndLaesstPrimefacesAttributeStehen(@TempDir Path tmp) throws IOException {
        Path res = Files.createDirectories(tmp.resolve("META-INF/resources"));

        // Violation 1: <script> with a body, and <h:outputScript> with a body.
        Files.writeString(res.resolve("badScript.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <script type="text/javascript">
                        window.alert('hallo');
                    </script>
                    <h:outputScript>
                        history.pushState(null, null, '/x.html');
                    </h:outputScript>
                </ui:composition>
                """);
        // Violation 2: on… attributes on real HTML elements.
        Files.writeString(res.resolve("badHandler.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <a href="#" onclick="tuWas(); return false;">los</a>
                    <select onchange="if(this.value){window.location.href=this.value;}">
                        <option value="">…</option>
                    </select>
                    <span onmouseover="this.style.opacity='1'">x</span>
                </ui:composition>
                """);
        // NO violation: external scripts, PrimeFaces attributes, data attributes,
        // an on… example inside a comment and a JS property in the body of an external script.
        Files.writeString(res.resolve("ok.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <!-- frueher: <a onclick="tuWas()"> und <img src=x onerror=alert(1)> -->
                    <h:outputScript library="plaintext-layout" name="js/menu.js"/>
                    <script src="/plaintext-layout/js/config.js"></script>
                    <p:commandButton value="Los" onstart="PF('b').show()" oncomplete="PF('b').hide()"
                                     onclick="return confirm('sicher?')"/>
                    <a href="#" data-pt-dropdown="menu" data-pt-only="x">Menu</a>
                </ui:composition>
                """);
        // NO violation: justified opt-out on the same line.
        Files.writeString(res.resolve("okOptOut.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <script>window.alert('x');</script> <!-- inline-js-ok: Fremdbibliothek verlangt es -->
                    <a href="#" onclick="tuWas()">los</a> <!-- inline-js-ok -->
                </ui:composition>
                """);

        List<Violation> violations = InlineJsLinter.scan(res);

        assertEquals(5, violations.size(), "Erwartet genau 5 Verstoesse, gefunden: " + violations);
        assertEquals(2, zaehle(violations, "badScript.xhtml", InlineJsLinter.RULE_INLINE_SCRIPT),
                "badScript: <script> UND <h:outputScript> mit Rumpf: " + violations);
        assertEquals(3, zaehle(violations, "badHandler.xhtml", InlineJsLinter.RULE_INLINE_HANDLER),
                "badHandler: onclick/onchange/onmouseover: " + violations);
        assertTrue(violations.stream().noneMatch(v -> v.file().getFileName().toString().startsWith("ok")),
                "ok*-Dateien duerfen keinen Verstoss liefern: " + violations);
    }

    @Test
    void allowlistNimmtGanzeDateiAusUndVerlangtBegruendung(@TempDir Path tmp) throws IOException {
        Path datei = tmp.resolve(ArchAllowlist.DATEINAME);
        Files.writeString(datei, """
                inline-js  plaintext-foo/src/main/resources/META-INF/resources/alt.xhtml  # Welle 4 laeuft, PR #1
                inline-js  plaintext-bar/src/main/resources/**                            # Altbestand, Karte 999
                jsf-view   plaintext-foo/src/main/java/X.java                             # andere Regel
                inline-js  plaintext-ohne/grund.xhtml
                """);

        ArchAllowlist a = ArchAllowlist.fuer(ALLOWLIST_REGEL, datei);

        assertTrue(a.erlaubt("plaintext-foo/src/main/resources/META-INF/resources/alt.xhtml"));
        assertTrue(a.erlaubt("plaintext-bar/src/main/resources/META-INF/resources/tief/pfad.xhtml"));
        assertTrue(!a.erlaubt("plaintext-foo/src/main/resources/META-INF/resources/neu.xhtml"));
        assertEquals(1, a.fehler().size(), "Eintrag ohne Begruendung muss Formfehler sein: " + a.fehler());
        assertTrue(a.fehler().get(0).contains("ohne Begruendung"));
    }

    @Test
    void scanAufNichtVorhandenemPfadLiefertLeereListe() {
        assertTrue(InlineJsLinter.scan(Path.of("does/not/exist/xyz")).isEmpty());
        assertTrue(InlineJsLinter.scan(null).isEmpty());
    }

    private static long zaehle(List<Violation> violations, String datei, String regel) {
        return violations.stream()
                .filter(v -> v.file().getFileName().toString().equals(datei))
                .filter(v -> v.rule().equals(regel))
                .count();
    }
}

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
 * Geteilter Vertragstest gegen Inline-JavaScript in ALLEN {@code src/main/resources/**}
 * {@code .xhtml} des jeweiligen Reactors — die Vorbedingung dafuer, dass die
 * Content-Security-Policy ohne {@code script-src 'unsafe-inline'} auskommt (Welle 4).
 *
 * <p><b>Warum das noetig ist.</b> {@code PlaintextSecurityConfig} setzt eine ausfuehrliche CSP,
 * aber {@code script-src} fuehrt {@code 'unsafe-inline'}. Damit laeuft jedes eingeschleuste
 * {@code <script>}: die Policy verhindert an dieser Stelle nichts. Sie laesst sich erst scharf
 * schalten, wenn KEIN eigenes Inline-JavaScript mehr im Markup steht — ein einziger verbliebener
 * Block irgendwo im Reactor genuegt, um den Schalter nicht umlegen zu koennen. Und weil neues
 * Inline-JavaScript beim Schreiben nie auffaellt (die Seite funktioniert ja), braucht es dafuer
 * einen Test und nicht eine Konvention.
 *
 * <p><b>Zwei Regeln</b> (Scan-Logik in {@link InlineJsLinter}, plaintext-root-common):
 * <ol>
 *   <li>{@code <script>} bzw. {@code <h:outputScript>} MIT Rumpf statt ausgelagerter Datei.</li>
 *   <li>{@code on…}-Attribut an einem Nicht-{@code p:}-Tag ({@code onclick} an {@code <a>},
 *       {@code onchange} an {@code <select>} …). {@code on…} an {@code p:}-Komponenten bleibt
 *       unbeanstandet: {@code oncomplete}/{@code onstart}/{@code onerror} sind
 *       PrimeFaces-Ereignisse des Ajax-Lebenszyklus und gar keine HTML-Attribute, und wo
 *       PrimeFaces wirklich einen HTML-Handler erzeugt ({@code onclick} an
 *       {@code p:commandButton}), zieht der CSP-Modus {@code joinfaces.primefaces.csp=true} ihn
 *       selbst heraus und registriert ihn ueber {@code PrimeFaces.csp}.</li>
 * </ol>
 *
 * <p><b>Scharf oder nur meldend — {@value #SCHALTER}.</b> Der Test laeuft in zwei Stellungen:
 * <ul>
 *   <li>{@code enforce}: jeder Verstoss laesst den Lauf fehlschlagen. So steht es in
 *       {@code plaintext-root-webapp/pom.xml} (Surefire {@code systemPropertyVariables}) — root
 *       ist seit Welle 4 sauber und bleibt es.</li>
 *   <li>{@code report} (Vorgabe): die Fundstellen werden auf {@code System.err} ausgegeben, der
 *       Test besteht. Das ist die Stellung fuer jeden Consumer (app, guild, iot, schuetu), der
 *       seinen Altbestand noch nicht bereinigt hat — ein root-Release darf dort keinen Build
 *       umwerfen, waehrend die Bereinigung noch laeuft. Ist ein Consumer durch, setzt er in
 *       seinem webapp-{@code pom.xml} dieselben zwei Zeilen wie root und ist ab da geschuetzt.</li>
 * </ul>
 * Die Vorgabe ist bewusst die schwaechere: eine Regel, die beim Einspielen fremde Builds bricht,
 * wird abgeschaltet statt befolgt.
 *
 * <p><b>Ausnahmen:</b> {@code <!-- inline-js-ok -->} in derselben Zeile nimmt einen einzelnen
 * Treffer aus; ganze Dateien nimmt die Allowlist des Reactors aus
 * ({@code plaintext-arch-allowlist.txt}, Regel {@code inline-js}, Begruendung Pflicht — siehe
 * {@link ArchAllowlist}). root fuehrt keine Allowlist.
 *
 * <p>Dieser Test liegt in {@code src/main/java} von {@code plaintext-root-archtests} und laeuft im
 * Consumer via Surefire {@code <dependenciesToScan>} ab dessen Reactor-Wurzel ueber jedes
 * Modul-{@code src/main/resources} (Pfadaufloesung: {@link ReactorLayout}) — nie ueber die
 * Ressourcen von root, die im Consumer als Jar vorliegen.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextInlineJsVertragTest {

    static final String ALLOWLIST_REGEL = "inline-js";

    /** System-Property, die ueber scharf ({@code enforce}) oder nur meldend ({@code report}) entscheidet. */
    static final String SCHALTER = "plaintext.arch.inline-js";

    static final String SCHARF = "enforce";

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * Scannt jedes {@code src/main/resources} aller Reactor-Module. Consumer ohne eigene XHTML
     * haben nichts zu linten — der Test besteht dann.
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
        // Meldend: ausgeben und bestehen lassen. Scharf schalten mit
        // -Dplaintext.arch.inline-js=enforce bzw. den zwei Surefire-Zeilen aus root.
        System.err.println(msg + "\nNUR GEMELDET (" + SCHALTER + " != " + SCHARF + "). Nach der Bereinigung\n"
                + "in <systemPropertyVariables> des webapp-Moduls " + SCHALTER + "=" + SCHARF + " setzen.\n");
    }

    static boolean scharf() {
        return SCHARF.equalsIgnoreCase(System.getProperty(SCHALTER, "report"));
    }

    @Test
    void linterErkenntBeideRegelnUndLaesstPrimefacesAttributeStehen(@TempDir Path tmp) throws IOException {
        Path res = Files.createDirectories(tmp.resolve("META-INF/resources"));

        // Verstoss 1: <script> mit Rumpf, und <h:outputScript> mit Rumpf.
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
        // Verstoss 2: on…-Attribute an echten HTML-Elementen.
        Files.writeString(res.resolve("badHandler.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <a href="#" onclick="tuWas(); return false;">los</a>
                    <select onchange="if(this.value){window.location.href=this.value;}">
                        <option value="">…</option>
                    </select>
                    <span onmouseover="this.style.opacity='1'">x</span>
                </ui:composition>
                """);
        // KEIN Verstoss: ausgelagerte Skripte, PrimeFaces-Attribute, data-Attribute,
        // ein on…-Beispiel im Kommentar und eine JS-Eigenschaft im Rumpf eines externen Skripts.
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
        // KEIN Verstoss: begruendetes Opt-out in derselben Zeile.
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

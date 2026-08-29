/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import ch.plaintext.boot.utils.JsfViewLinter;
import ch.plaintext.boot.utils.JsfViewLinter.Violation;
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
 * Geteilter Linter-Guard gegen drei JSF-View-Fallstricke in ALLEN {@code src/main/resources/**}
 * {@code .xhtml} des jeweiligen Reactors (Zustandsbericht 29.08.2026, Paket R2).
 *
 * <p><b>Die drei Vorfaelle, die diesen Test noetig machen:</b>
 * <ol>
 *   <li><b>{@code <f:metadata>} nach dem ersten {@code <ui:define>}</b> — 20 root-Seiten hatten den
 *       Block in einem {@code ui:define} (Titel/Content/Page). Das Template
 *       {@code /includes/template.xhtml} hat keinen Metadaten-Insert; {@code preRenderView}-Listener,
 *       {@code f:viewParam} und {@code f:viewAction} sind dort unwirksam bzw. unzuverlaessig.
 *       Der Block gehoert direkt unter {@code <ui:composition ...>} (Vorlage {@code menudiagnose.xhtml}).</li>
 *   <li><b>{@code <f:metadata>} innerhalb {@code <h:form>}</b> — 16 dieser 20 Seiten. Zusaetzlich
 *       laeuft der Listener bei jedem Ajax-Postback des Formulars mit und ueberschreibt Daten, die
 *       der Benutzer gerade bearbeitet.</li>
 *   <li><b>{@code onchange="submit()"} an einer {@code p:}-Komponente</b> — an der Typ-Auswahl der
 *       Datenverwaltung ({@code rootentities.xhtml}) loeste PrimeFaces 15 darueber keinen Request
 *       aus; die Auswahl blieb wirkungslos, keine Tabelle erschien. Richtig ist {@code <p:ajax>}.</li>
 * </ol>
 *
 * <p><b>Ausnahmen:</b> {@code <!-- jsf-view-ok -->} in derselben Zeile nimmt einen einzelnen
 * Treffer aus (wie {@code mobile-ok} / {@code el-quote-ok}); ganze Dateien nimmt die Allowlist des
 * Reactors aus ({@code plaintext-arch-allowlist.txt}, Regel {@code jsf-view}, Begruendung Pflicht —
 * siehe {@link ArchAllowlist}). root fuehrt keine Allowlist.
 *
 * <p>Der Scan-Code lebt in {@link JsfViewLinter} (plaintext-root-common). Dieser Test liegt in
 * {@code src/main/java} von {@code plaintext-root-archtests} und laeuft im Consumer via Surefire
 * {@code <dependenciesToScan>} ab dessen Reactor-Wurzel ueber jedes Modul-{@code src/main/resources}
 * (Pfadaufloesung: {@link ReactorLayout}).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextJsfViewLinterTest {

    static final String ALLOWLIST_REGEL = "jsf-view";

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * Scannt jedes {@code src/main/resources} aller Reactor-Module und schlaegt bei jedem Verstoss
     * mit Datei, Zeile und Regel fehl. Consumer ohne eigene XHTML haben nichts zu linten — der Test
     * besteht dann.
     */
    @Test
    void keineJsfViewFallstrickeInXhtml() {
        List<Path> resourceRoots = ReactorLayout.sourceRoots(RESOURCES_SUFFIX);
        if (resourceRoots.isEmpty()) {
            return;
        }
        ArchAllowlist allowlist = ArchAllowlist.fuer(ALLOWLIST_REGEL);

        List<String> violations = new ArrayList<>(allowlist.fehler());
        for (Path root : resourceRoots) {
            for (Violation v : JsfViewLinter.scan(root)) {
                String rel = ReactorLayout.relativ(v.file());
                if (!allowlist.erlaubt(rel)) {
                    violations.add(rel + ":" + v.line() + " [" + v.rule() + "] -> " + v.message());
                }
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder("\n\n=== JSF-VIEW-FALLSTRICKE (Zustandsbericht 29.08.2026) ===\n");
            violations.forEach(v -> msg.append("  ! ").append(v).append("\n"));
            msg.append("\nf:metadata gehoert direkt unter <ui:composition ...>, vor das erste <ui:define> und\n")
               .append("ausserhalb jedes <h:form> (Vorlage menudiagnose.xhtml). onchange=\"submit()\" an p:-\n")
               .append("Komponenten loest in PrimeFaces 15 nichts aus -> <p:ajax listener/update>.\n")
               .append("Begruendete Ausnahme: <!-- ").append(JsfViewLinter.EXEMPT_COMMENT).append(" --> in derselben\n")
               .append("Zeile oder Eintrag '").append(ALLOWLIST_REGEL).append(" <pfad>  # <Grund>' in ")
               .append(ArchAllowlist.DATEINAME).append(".\n");
            fail(msg.toString());
        }
    }

    @Test
    void linterErkenntAlleDreiRegelnUndRespektiertOptOut(@TempDir Path tmp) throws IOException {
        Path res = Files.createDirectories(tmp.resolve("META-INF/resources"));

        // Verstoss 1+2: metadata in ui:define UND in h:form (zwei Meldungen, eine Datei).
        Files.writeString(res.resolve("badForm.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <ui:define name="content">
                        <h:form id="fm">
                            <f:metadata>
                                <f:event type="preRenderView" listener="#{bean.onLoad()}"/>
                            </f:metadata>
                        </h:form>
                    </ui:define>
                </ui:composition>
                """);
        // Verstoss 1 allein: metadata in ui:define, aber ausserhalb eines Formulars.
        Files.writeString(res.resolve("badDefine.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <ui:define name="page">
                        <f:metadata><f:viewParam name="id" value="#{bean.id}"/></f:metadata>
                    </ui:define>
                </ui:composition>
                """);
        // Verstoss 3: onchange="submit()" bzw. this.form.submit() an p:-Komponenten (auch mehrzeilig).
        Files.writeString(res.resolve("badSubmit.xhtml"), """
                <h:form>
                    <p:selectOneMenu value="#{bean.typ}" onchange="submit()"/>
                    <p:selectOneRadio value="#{bean.x}"
                                      onchange="this.form.submit()"/>
                </h:form>
                """);
        // KEIN Verstoss: metadata direkt unter ui:composition (Vorlage menudiagnose.xhtml), p:ajax statt submit.
        Files.writeString(res.resolve("ok.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <f:metadata>
                        <f:event type="preRenderView" listener="#{bean.onLoad()}"/>
                    </f:metadata>
                    <ui:define name="content">
                        <h:form id="fm">
                            <p:selectOneMenu value="#{bean.typ}">
                                <p:ajax listener="#{bean.typGewechselt}" update="@form"/>
                            </p:selectOneMenu>
                        </h:form>
                    </ui:define>
                </ui:composition>
                """);
        // KEIN Verstoss: Standalone-Seite mit h:body (kein ui:define), h:-Komponente statt p:, Beispiel im Kommentar.
        Files.writeString(res.resolve("okStandalone.xhtml"), """
                <h:body>
                    <f:metadata><f:viewParam name="id" value="#{bean.id}"/></f:metadata>
                    <!-- frueher: <p:selectOneMenu onchange="submit()"/> -->
                    <h:form><h:selectOneMenu value="#{bean.typ}" onchange="submit()"/></h:form>
                </h:body>
                """);
        // KEIN Verstoss: begruendetes Opt-out in derselben Zeile.
        Files.writeString(res.resolve("okOptOut.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <ui:define name="content">
                        <h:form id="fm">
                            <f:metadata> <!-- jsf-view-ok: eigener Insert im Sondertemplate -->
                                <f:event type="preRenderView" listener="#{bean.onLoad()}"/>
                            </f:metadata>
                            <p:selectOneMenu value="#{bean.typ}" onchange="submit()"/> <!-- jsf-view-ok -->
                        </h:form>
                    </ui:define>
                </ui:composition>
                """);

        List<Violation> violations = JsfViewLinter.scan(res);

        assertEquals(5, violations.size(), "Erwartet genau 5 Verstoesse, gefunden: " + violations);
        assertTrue(hat(violations, "badForm.xhtml", JsfViewLinter.RULE_METADATA_NACH_DEFINE), "badForm/define: " + violations);
        assertTrue(hat(violations, "badForm.xhtml", JsfViewLinter.RULE_METADATA_IN_FORM), "badForm/form: " + violations);
        assertTrue(hat(violations, "badDefine.xhtml", JsfViewLinter.RULE_METADATA_NACH_DEFINE), "badDefine: " + violations);
        assertEquals(2, violations.stream()
                .filter(v -> v.file().getFileName().toString().equals("badSubmit.xhtml"))
                .filter(v -> v.rule().equals(JsfViewLinter.RULE_ONCHANGE_SUBMIT)).count(),
                "badSubmit: beide Schreibweisen muessen erkannt werden: " + violations);
        assertTrue(violations.stream().noneMatch(v -> v.file().getFileName().toString().startsWith("ok")),
                "ok*-Dateien duerfen keinen Verstoss liefern: " + violations);
    }

    @Test
    void allowlistNimmtGanzeDateiAusUndVerlangtBegruendung(@TempDir Path tmp) throws IOException {
        Path datei = tmp.resolve(ArchAllowlist.DATEINAME);
        Files.writeString(datei, """
                # Beispiel-Allowlist
                jsf-view   plaintext-foo/src/main/resources/META-INF/resources/alt.xhtml   # eigener Metadaten-Insert
                jsf-view   plaintext-bar/src/main/resources/**                            # Altbestand, Karte 999
                header-hygiene plaintext-foo/src/main/java/X.java # andere Regel, hier ohne Wirkung
                jsf-view   plaintext-ohne/grund.xhtml
                """);

        ArchAllowlist a = ArchAllowlist.fuer(ALLOWLIST_REGEL, datei);

        assertTrue(a.erlaubt("plaintext-foo/src/main/resources/META-INF/resources/alt.xhtml"));
        assertTrue(a.erlaubt("plaintext-bar/src/main/resources/META-INF/resources/tief/er/pfad.xhtml"));
        assertTrue(!a.erlaubt("plaintext-foo/src/main/resources/META-INF/resources/neu.xhtml"));
        assertTrue(!a.erlaubt("plaintext-foo/src/main/java/X.java"), "Eintrag einer anderen Regel darf nicht greifen");
        assertEquals(1, a.fehler().size(), "Eintrag ohne Begruendung muss als Formfehler gemeldet werden: " + a.fehler());
        assertTrue(a.fehler().get(0).contains("ohne Begruendung"));
    }

    @Test
    void scanAufNichtVorhandenemPfadLiefertLeereListe() {
        assertTrue(JsfViewLinter.scan(Path.of("does/not/exist/xyz")).isEmpty());
        assertTrue(JsfViewLinter.scan(null).isEmpty());
    }

    private static boolean hat(List<Violation> violations, String datei, String regel) {
        return violations.stream().anyMatch(v -> v.file().getFileName().toString().equals(datei) && v.rule().equals(regel));
    }
}

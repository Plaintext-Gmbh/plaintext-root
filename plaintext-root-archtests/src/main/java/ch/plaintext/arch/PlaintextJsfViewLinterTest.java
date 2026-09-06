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
 * Shared linter guard against four JSF view pitfalls in ALL {@code src/main/resources/**}
 * {@code .xhtml} of the respective reactor (status report 29.08.2026, package R2).
 *
 * <p><b>The four incidents that make this test necessary:</b>
 * <ol>
 *   <li><b>{@code <f:metadata>} after the first {@code <ui:define>}</b> — 20 root pages had the block
 *       inside a {@code ui:define} (title/content/page). The template
 *       {@code /includes/template.xhtml} has no metadata insert; {@code preRenderView} listeners,
 *       {@code f:viewParam} and {@code f:viewAction} are ineffective resp. unreliable there.
 *       The block belongs directly below {@code <ui:composition ...>} (model: {@code menudiagnose.xhtml}).</li>
 *   <li><b>{@code <f:metadata>} inside {@code <h:form>}</b> — 16 of those 20 pages. On top of that
 *       the listener also runs on every Ajax postback of the form and overwrites data the user is
 *       currently editing.</li>
 *   <li><b>{@code onchange="submit()"} on a {@code p:} component</b> — on the type selector of the
 *       data administration ({@code rootentities.xhtml}) PrimeFaces 15 triggered no request through
 *       it; the selection had no effect, no table appeared. The correct form is {@code <p:ajax>}.</li>
 *   <li><b>Ajax search expression through a non-NamingContainer id</b> — {@code update=":fm:dlg:qEvent"}
 *       in guild's {@code listen.xhtml}, where {@code dlg} is a {@code <p:dialog>}. JSF throws
 *       {@code IllegalArgumentException: dlg} while rendering; for the user the dialog opens as an
 *       empty shell and the modal overlay never lets go of the page (Daniel, 31.08.2026). Correct is
 *       {@code ":fm:qEvent"} — the dialog creates no namespace.</li>
 * </ol>
 *
 * <p><b>Exceptions:</b> {@code <!-- jsf-view-ok -->} on the same line exempts a single hit (like
 * {@code mobile-ok} / {@code el-quote-ok}); whole files are exempted by the reactor's allowlist
 * ({@code plaintext-arch-allowlist.txt}, rule {@code jsf-view}, justification mandatory —
 * see {@link ArchAllowlist}). root keeps no allowlist.
 *
 * <p>The scan code lives in {@link JsfViewLinter} (plaintext-root-common). This test lives in
 * {@code src/main/java} of {@code plaintext-root-archtests} and runs in the consumer via Surefire
 * {@code <dependenciesToScan>} from that consumer's reactor root over every module's
 * {@code src/main/resources} (path resolution: {@link ReactorLayout}).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextJsfViewLinterTest {

    static final String ALLOWLIST_REGEL = "jsf-view";

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * Scans every {@code src/main/resources} of all reactor modules and fails on every violation with
     * file, line and rule. Consumers without XHTML of their own have nothing to lint — the test then
     * passes.
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
               .append("Ein Suchausdruck darf in der Mitte nur NamingContainer passieren: p:dialog ist keiner,\n")
               .append("\":fm:dlg:feld\" wirft beim Rendern IllegalArgumentException -> \":fm:feld\".\n")
               .append("Begruendete Ausnahme: <!-- ").append(JsfViewLinter.EXEMPT_COMMENT).append(" --> in derselben\n")
               .append("Zeile oder Eintrag '").append(ALLOWLIST_REGEL).append(" <pfad>  # <Grund>' in ")
               .append(ArchAllowlist.DATEINAME).append(".\n");
            fail(msg.toString());
        }
    }

    @Test
    void linterErkenntAlleDreiRegelnUndRespektiertOptOut(@TempDir Path tmp) throws IOException {
        Path res = Files.createDirectories(tmp.resolve("META-INF/resources"));

        // Violation 1+2: metadata in ui:define AND in h:form (two messages, one file).
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
        // Violation 1 alone: metadata in ui:define, but outside a form.
        Files.writeString(res.resolve("badDefine.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <ui:define name="page">
                        <f:metadata><f:viewParam name="id" value="#{bean.id}"/></f:metadata>
                    </ui:define>
                </ui:composition>
                """);
        // Violation 3: onchange="submit()" resp. this.form.submit() on p: components (also multi-line).
        Files.writeString(res.resolve("badSubmit.xhtml"), """
                <h:form id="fm">
                    <p:selectOneMenu value="#{bean.typ}" onchange="submit()"/>
                    <p:selectOneRadio value="#{bean.x}"
                                      onchange="this.form.submit()"/>
                </h:form>
                """);
        // NO violation: metadata directly below ui:composition (model menudiagnose.xhtml), p:ajax instead of submit.
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
        // NO violation: standalone page with h:body (no ui:define), h: component instead of p:, example inside a comment.
        Files.writeString(res.resolve("okStandalone.xhtml"), """
                <h:body>
                    <f:metadata><f:viewParam name="id" value="#{bean.id}"/></f:metadata>
                    <!-- frueher: <p:selectOneMenu onchange="submit()"/> -->
                    <h:form id="fm"><h:selectOneMenu value="#{bean.typ}" onchange="submit()"/></h:form>
                </h:body>
                """);
        // NO violation: justified opt-out on the same line.
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
    void linterErkenntSuchausdruckDurchNichtNamingContainer(@TempDir Path tmp) throws IOException {
        Path res = Files.createDirectories(tmp.resolve("META-INF/resources"));

        // Verstoss: der Ausdruck laeuft durch die ID eines p:dialog — genau der guild-Fall vom 31.08.2026.
        Files.writeString(res.resolve("badDialog.xhtml"), """
                <h:form id="fm">
                    <p:dialog id="dlg" widgetVar="dlgListe">
                        <p:toggleSwitch id="qEvents" value="#{bean.a}">
                            <p:ajax update=":fm:dlg:qEvent" process="@this"/>
                        </p:toggleSwitch>
                        <p:selectOneMenu id="qEvent" value="#{bean.b}"/>
                    </p:dialog>
                </h:form>
                """);
        // KEIN Verstoss: derselbe Aufbau, aber ohne den Dialog im Pfad — so ist es richtig.
        Files.writeString(res.resolve("okDialog.xhtml"), """
                <h:form id="fm">
                    <p:dialog id="dlg" widgetVar="dlgListe">
                        <p:toggleSwitch id="qEvents" value="#{bean.a}">
                            <p:ajax update=":fm:qEvent" process="@this"/>
                        </p:toggleSwitch>
                        <p:selectOneMenu id="qEvent" value="#{bean.b}"/>
                    </p:dialog>
                    <p:commandButton update=":fm:dlg" oncomplete="PF('dlgListe').show()"/>
                </h:form>
                """);
        // KEIN Verstoss: ein dataTable IST ein NamingContainer und darf in der Mitte stehen.
        Files.writeString(res.resolve("okTabelle.xhtml"), """
                <h:form id="fm">
                    <p:dataTable id="tbl" var="z" value="#{bean.zeilen}">
                        <p:column><p:inputText id="feld" value="#{z.wert}"/></p:column>
                    </p:dataTable>
                    <p:commandButton update=":fm:tbl:feld"/>
                </h:form>
                """);

        List<Violation> violations = JsfViewLinter.scan(res);

        assertEquals(1, violations.size(), "Erwartet genau einen Verstoss, gefunden: " + violations);
        assertTrue(hat(violations, "badDialog.xhtml", JsfViewLinter.RULE_SUCHAUSDRUCK_DURCH_NICHT_CONTAINER),
                "badDialog muss gemeldet werden: " + violations);
        assertTrue(violations.get(0).message().contains("dlg"),
                "Die Meldung muss die schuldige ID nennen: " + violations.get(0).message());
    }

    @Test
    void linterErkenntFormOhneId(@TempDir Path tmp) throws IOException {
        Path res = Files.createDirectories(tmp.resolve("META-INF/resources"));

        // Verstoss: kein id-Attribut ueberhaupt — genau die beiden root-Faelle vom 05.09.2026
        // (entityverwaltung.xhtml, debug.xhtml).
        Files.writeString(res.resolve("badOhneId.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <ui:define name="content">
                        <h:form>
                            <input type="hidden" name="_csrf" value="#{_csrf.token}"/>
                        </h:form>
                    </ui:define>
                </ui:composition>
                """);
        // KEIN Verstoss: einzelnes Formular auf der Seite, id="fm".
        Files.writeString(res.resolve("okEinfach.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <ui:define name="content">
                        <h:form id="fm">
                            <input type="hidden" name="_csrf" value="#{_csrf.token}"/>
                        </h:form>
                    </ui:define>
                </ui:composition>
                """);
        // KEIN Verstoss: mehrere Formulare, je ein sprechender Name (nicht zwingend "fm"-Praefix,
        // die Regel prueft nur, DASS ueberhaupt eine Id gesetzt ist).
        Files.writeString(res.resolve("okMehrere.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <ui:define name="content">
                        <h:form id="selectorForm">
                            <input type="hidden" name="_csrf" value="#{_csrf.token}"/>
                        </h:form>
                        <h:form id="listForm">
                            <input type="hidden" name="_csrf" value="#{_csrf.token}"/>
                        </h:form>
                    </ui:define>
                </ui:composition>
                """);
        // KEIN Verstoss: begruendete Ausnahme in derselben Zeile.
        Files.writeString(res.resolve("okOptOut.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <ui:define name="content">
                        <h:form> <!-- jsf-view-ok: Platzhalter, wird in Karte 1234 entfernt -->
                            <input type="hidden" name="_csrf" value="#{_csrf.token}"/>
                        </h:form>
                    </ui:define>
                </ui:composition>
                """);

        List<Violation> violations = JsfViewLinter.scan(res);

        assertEquals(1, violations.size(), "Erwartet genau einen Verstoss, gefunden: " + violations);
        assertTrue(hat(violations, "badOhneId.xhtml", JsfViewLinter.RULE_FORM_OHNE_ID), "badOhneId: " + violations);
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

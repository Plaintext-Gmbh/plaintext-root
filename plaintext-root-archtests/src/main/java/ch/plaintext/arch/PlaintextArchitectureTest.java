/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import ch.plaintext.PlaintextCron;
import ch.plaintext.boot.plugins.jsf.FacesMessages;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Scheduled;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Shared architecture rules of the plaintext-root framework (ArchUnit, pure bytecode analysis — no
 * Spring context, no DB).
 *
 * <p><b>Reuse across module boundaries:</b> this class deliberately lives in
 * {@code src/main/java} of the shared test module {@code plaintext-root-archtests} (not in
 * {@code src/test}), so that it ends up in the published jar. Consumers (app, iot, fwtool, schuetu)
 * take the module as a test dependency and let the rules run via Surefire
 * {@code <dependenciesToScan>} against their own {@code ch.plaintext} classes — no more
 * copy-paste. When running inside a consumer, {@code @AnalyzeClasses(packages =
 * "ch.plaintext")} analyzes that consumer's classpath classes.
 *
 * <p><b>Core rule:</b> scheduling runs through the PlaintextCron framework ({@link PlaintextCron}:
 * admin UI with schedule/on-off, per-tenant execution, runtime statistics). Raw Spring
 * {@code @Scheduled} bypasses all of that and is therefore forbidden — except for the few classes
 * deliberately annotated with {@link AllowRawScheduled} (framework/system guards with sub-minute
 * or self-maintenance intervals that a cron expression cannot express). The rule is thereby fully
 * generic and extensible by consumers: a consumer simply annotates its own legitimate
 * {@code @Scheduled} class with {@code @AllowRawScheduled} (main classpath, from
 * {@code plaintext-root-common}) — entirely without test code of its own. Every new periodic job
 * belongs in the cron framework.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@AnalyzeClasses(packages = "ch.plaintext", importOptions = ImportOption.DoNotIncludeTests.class)
public class PlaintextArchitectureTest {

    /**
     * Fully qualified name of the marker annotation {@link AllowRawScheduled}. Referenced as a string
     * so that the rule applies regardless of whether the annotation is resolvable on the classpath at
     * analysis time.
     */
    private static final String ALLOW_RAW_SCHEDULED = AllowRawScheduled.class.getName();

    @ArchTest
    static final ArchRule keinRohesScheduled = methods()
            .that().areAnnotatedWith(Scheduled.class)
            .should(nurInMitAllowRawScheduledAnnotierterKlasse())
            .because("Zeitsteuerung gehört ins PlaintextCron-Framework (Admin-UI, per-Mandant, "
                    + "Statistik); begründete Ausnahmen tragen @AllowRawScheduled an der Klasse");

    @ArchTest
    static final ArchRule plaintextCronsSindPrototype = classes()
            .that().implement(PlaintextCron.class).and().areNotInterfaces()
            // Exempt framework internals: the abstract base SuperCron and the wrapper it creates via a
            // BeanPostProcessor (CronBeanPostProcessor$1) implement PlaintextCron, but are NOT application
            // cron beans and are legitimately not @Scope("prototype"). The rule targets the concrete
            // application crons that live outside ch.plaintext.cron.
            .and().resideOutsideOfPackage("ch.plaintext.cron..")
            .should(mitScopePrototypeAnnotiert())
            .because("der CronController holt pro Mandant-Lauf eine frische Instanz; ein Singleton "
                    + "würde per setMandant() überschrieben (CronBeanPostProcessor bricht den Boot ab)");

    /**
     * JSF backing beans run session-scoped in the plaintext-root framework: {@code @Component} +
     * {@code @Scope("session")} plus a {@code preRenderView} listener {@code #{bean.onLoad()}} with an
     * {@code isPostback} guard, which reloads the data freshly on every GET. The ViewScoped annotation
     * is thereby superseded and forbidden; it would force JSF view-state serialization of its own.
     *
     * <p>This rule only covers the modules visible on the consumer classpath. The cross-module
     * source-code scan (incl. modules that do not depend on the webapp) lives in
     * {@code PlaintextViewScopedBanTest}.
     */
    @ArchTest
    static final ArchRule keineViewScopedBeans = noClasses()
            .should().beAnnotatedWith("jakarta.faces.view.ViewScoped")
            .because("Backing-Beans laufen session-scoped (@Scope(\"session\")) mit preRenderView-onLoad(); "
                    + "die ViewScoped-Annotation ist im plaintext-root-Framework abgeloest");

    /**
     * A-05 (Analyse 05.09.2026, Karte 1104): Benutzer-Meldungen gehen über {@link FacesMessages}
     * (info/warn/error/meldung/feld) statt über den rohen {@code FacesContext.addMessage}-Aufruf.
     * War bislang nur app-lokal als {@code meldungenNurUeberFacesMessages} in
     * {@code plaintext-app-webapp}s eigenem {@code ArchitectureTest} durchgesetzt (dort mit einem
     * {@code NurAppCode}-Importfilter, der die root-Framework-JARs — und damit auch
     * {@link FacesMessages} selbst — von der Analyse ausschliesst); hier als geteilte Regel
     * übernommen, mit derselben Ausnahme direkt über {@code areNotAssignableTo(FacesMessages.class)},
     * weil {@link FacesMessages} den Aufruf naturgemäss selbst kapselt.
     */
    @ArchTest
    static final ArchRule meldungenNurUeberFacesMessages = noClasses()
            .that().areNotAssignableTo(FacesMessages.class)
            .should().callMethod(jakarta.faces.context.FacesContext.class, "addMessage",
                    String.class, jakarta.faces.application.FacesMessage.class)
            .because("FacesMessages.info/warn/error/meldung/feld statt FacesContext.addMessage "
                    + "(root-common) — zentrale Stelle für Null-Sicherheit ausserhalb eines "
                    + "JSF-Requests statt N leicht unterschiedlicher Kopien");

    // ── Conditions ─────────────────────────────────────────────────────────

    private static ArchCondition<JavaMethod> nurInMitAllowRawScheduledAnnotierterKlasse() {
        return new ArchCondition<>("nur in einer mit @AllowRawScheduled annotierten Klasse deklariert sein") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                JavaClass owner = method.getOwner();
                if (!owner.isAnnotatedWith(ALLOW_RAW_SCHEDULED)) {
                    events.add(SimpleConditionEvent.violated(method, owner.getFullName() + "." + method.getName()
                            + "() nutzt rohes @Scheduled — Zeitsteuerung gehört ins PlaintextCron-Framework. "
                            + "Echte Framework-/System-Waechter tragen @AllowRawScheduled an der Klasse."));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> mitScopePrototypeAnnotiert() {
        return new ArchCondition<>("mit @Scope(\"prototype\") annotiert sein") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean prototype = item.tryGetAnnotationOfType(Scope.class)
                        .map(s -> "prototype".equals(s.value()) || "prototype".equals(s.scopeName()))
                        .orElse(false);
                if (!prototype) {
                    events.add(SimpleConditionEvent.violated(item, item.getFullName()
                            + " implementiert PlaintextCron, ist aber nicht @Scope(\"prototype\")"));
                }
            }
        };
    }
}

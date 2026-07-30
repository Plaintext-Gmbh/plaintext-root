/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import ch.plaintext.PlaintextCron;
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
 * Geteilte Architektur-Regeln des plaintext-root-Frameworks (ArchUnit, reine Bytecode-Analyse — kein
 * Spring-Kontext, keine DB).
 *
 * <p><b>Wiederverwendung ueber Modulgrenzen:</b> Diese Klasse liegt bewusst in
 * {@code src/main/java} des geteilten Test-Moduls {@code plaintext-root-archtests} (nicht in
 * {@code src/test}), damit sie ins publizierte Jar wandert. Consumer (app, iot, fwtool, schuetu)
 * nehmen das Modul als Test-Dependency auf und lassen die Regeln via Surefire
 * {@code <dependenciesToScan>} gegen ihre eigenen {@code ch.plaintext}-Klassen laufen — kein
 * Copy-Paste mehr. Beim Lauf in einem Consumer analysiert {@code @AnalyzeClasses(packages =
 * "ch.plaintext")} dessen Classpath-Klassen.
 *
 * <p><b>Kernregel:</b> Zeitsteuerung laeuft ueber das PlaintextCron-Framework ({@link PlaintextCron}:
 * Admin-UI mit Zeitplan/an-aus, per-Mandant-Ausfuehrung, Laufzeit-Statistik). Rohes Spring-
 * {@code @Scheduled} umgeht das alles und ist deshalb verboten — bis auf die wenigen Klassen, die
 * bewusst mit {@link AllowRawScheduled} annotiert sind (Framework-/System-Waechter mit Sub-Minuten-
 * bzw. Selbst-Wartungs-Takten, die eine Cron-Expression nicht abbilden kann). Die Regel ist damit
 * voll generisch und consumer-erweiterbar: ein Consumer annotiert seine legitime eigene
 * {@code @Scheduled}-Klasse einfach mit {@code @AllowRawScheduled} (Main-Classpath, aus
 * {@code plaintext-root-common}) — ganz ohne eigenen Test-Code. Jeder neue periodische Job gehoert
 * ins Cron-Framework.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@AnalyzeClasses(packages = "ch.plaintext", importOptions = ImportOption.DoNotIncludeTests.class)
public class PlaintextArchitectureTest {

    /**
     * Voll qualifizierter Name der Marker-Annotation {@link AllowRawScheduled}. Als String referenziert,
     * damit die Regel unabhaengig davon greift, ob die Annotation zur Analysezeit auf dem Classpath
     * aufloesbar ist.
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
            // Framework-Interna ausnehmen: die abstrakte Basis SuperCron und der von ihr per
            // BeanPostProcessor erzeugte Wrapper (CronBeanPostProcessor$1) implementieren PlaintextCron,
            // sind aber KEINE Anwendungs-Cron-Beans und legitim nicht @Scope("prototype"). Die Regel gilt
            // den konkreten, ausserhalb ch.plaintext.cron liegenden Anwendungs-Crons.
            .and().resideOutsideOfPackage("ch.plaintext.cron..")
            .should(mitScopePrototypeAnnotiert())
            .because("der CronController holt pro Mandant-Lauf eine frische Instanz; ein Singleton "
                    + "würde per setMandant() überschrieben (CronBeanPostProcessor bricht den Boot ab)");

    /**
     * JSF-Backing-Beans laufen im plaintext-root-Framework session-scoped: {@code @Component} +
     * {@code @Scope("session")} plus ein {@code preRenderView}-Listener {@code #{bean.onLoad()}} mit
     * {@code isPostback}-Guard, der die Daten bei jedem GET frisch laedt. Die ViewScoped-Annotation ist
     * damit abgeloest und verboten; sie wuerde eigene JSF-View-State-Serialisierung erzwingen.
     *
     * <p>Diese Regel deckt nur die auf dem Consumer-Classpath sichtbaren Module ab. Der modul-
     * uebergreifende Quelltext-Scan (inkl. Modulen, die nicht von der webapp abhaengen) lebt in
     * {@code PlaintextViewScopedBanTest}.
     */
    @ArchTest
    static final ArchRule keineViewScopedBeans = noClasses()
            .should().beAnnotatedWith("jakarta.faces.view.ViewScoped")
            .because("Backing-Beans laufen session-scoped (@Scope(\"session\")) mit preRenderView-onLoad(); "
                    + "die ViewScoped-Annotation ist im plaintext-root-Framework abgeloest");

    // ── Bedingungen ────────────────────────────────────────────────────────

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

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared layering guardrail (status report 29.08.2026, measure 12): the web layer may reach
 * downwards, the service layer must not reach upwards.
 *
 * <p><b>Trigger.</b> In schuetu half the application lives in the package
 * {@code ch.plaintext.schuetu.service} — including backing beans and PrimeFaces {@code
 * LazyDataModel}s. That is no cosmetic flaw: a class that pulls in {@code FacesContext} can no
 * longer be called outside a JSF request (no cron, no REST, no test without a Faces mock), and it
 * forces every module that needs the service to take the whole JSF chain onto its classpath. The
 * damage only shows up when somebody wants to reuse the service — that is, late.
 *
 * <p><b>What is checked.</b>
 * <ul>
 *   <li><b>L1</b> — a class in a package with the segment {@code service} or {@code services}
 *       does not depend on {@code jakarta.faces..} or {@code org.primefaces..} (import, field,
 *       parameter, return type, call, annotation — everything ArchUnit sees as a dependency).
 *       Rule identifier {@value #REGEL_L1}.</li>
 *   <li><b>L2</b> — a class with the name suffix {@code BackingBean} does not lie in a package
 *       with the segment {@code service}, {@code services}, {@code repository}, {@code repositories}
 *       or {@code jpa}, unless the same package also names the web layer (segment
 *       {@code web}, {@code view(s)}, {@code ui}, {@code gui}, {@code jsf}, {@code bean(s)}).
 *       Rule identifier {@value #REGEL_L2}.</li>
 * </ul>
 *
 * <p><b>Why {@code jakarta.servlet..} is NOT part of L1.</b> The first cut had it in and thereby hit
 * exactly one root class: {@code ch.plaintext.sessions.service.HttpSessionRegistry},
 * a registry of live {@code jakarta.servlet.http.HttpSession} objects. Its very subject
 * <em>is</em> the servlet session; without the servlet API the class does not exist. The choice was
 * between a permanent exception for a sensible class and a more honest cut —
 * and the report's finding is the JSF coupling, not the servlet API. A servlet filter or
 * a session registry is web infrastructure, but it forces JSF on nobody and runs in a
 * test without a Faces context. Deliberately accepted: a servlet filter that lies in the
 * {@code service} package (schuetu has two) no longer stands out here. That is a question
 * of package naming, not of layering.
 *
 * <p><b>Why L2 lets a web segment in the same package pass.</b> The module name must not
 * count against the class. {@code ch.plaintext.jpa.web.AdminEntityBackingBean} carries the segment
 * {@code jpa} because the module is called {@code plaintext-root-jpa} — the bean itself lies in
 * {@code .web}, that is exactly where a backing bean belongs. A rule that reports this
 * measures the module namespace instead of the layer and would start out with three exceptions for
 * correct code. Conversely it stays sharp where it counts: {@code …schuetu.service.MqttBackingBean}
 * or {@code …schuetu.service.einstellungen.EinstellungenBackingBean} name no
 * web layer anywhere and are therefore hits.
 *
 * <p><b>L3 (module cycles) deliberately does NOT exist here.</b> Additionally planned was
 * {@code slices().matching("ch.plaintext.(*)..").should().beFreeOfCycles()}. Measuring it against
 * the root code base of 30.08.2026 produced <b>31 cycle groups</b> across practically all top-level
 * packages; the hub is {@code ch.plaintext.boot}, which is used by {@code framework}, {@code modules},
 * {@code settings}, {@code mailtemplate}, {@code menuesteuerung}, {@code oidc}, {@code apitoken},
 * {@code audit}, {@code arch} and {@code bus} and itself uses every one of them (example:
 * {@code boot -> settings -> modules -> jpa -> boot}). A rule with some thirty exceptions checks
 * nothing, it merely documents — and whoever reads it takes it for fulfilled. Resolving the cycles
 * is a stage of its own (pull interfaces into {@code plaintext-root-interfaces}, then untangle
 * {@code boot}) and no side effect of this measure. Until then the finding stands here instead of
 * in a green test.
 *
 * <p><b>Why the scan hangs off the reactor and not off the base package.</b> Unlike
 * {@link PlaintextSessionBeanSerialisierbarTest} (where {@code ch.plaintext} from the jars is
 * wanted), this test imports exclusively the {@code target/classes} of the modules of its
 * own reactor ({@link ReactorLayout}). A consumer is supposed to judge its own code and
 * not to trip over root classes from jars it cannot change — an allowlist with
 * entries for foreign code loses its point. That root is green under both rules <em>without</em>
 * an exception (as of 30.08.2026) is therefore a precondition and no coincidence: root still
 * keeps no {@code plaintext-arch-allowlist.txt}.
 *
 * <p><b>A consequence of that:</b> only what has already been compiled by the time of the test run
 * is checked. In root this test therefore runs in two places: in {@code plaintext-root-webapp} (which
 * sees everything built before it) and in {@code plaintext-admin-requirements}, which only comes
 * afterwards and has no classes at all yet during the webapp run of a clean build. Together the
 * two runs cover the reactor. A module whose {@code target/classes} is missing in both runs
 * would stay unchecked — hence the positive control {@link #derTestSiehtEtwas()}.
 *
 * <p><b>Exceptions:</b> the reactor's allowlist ({@code plaintext-arch-allowlist.txt}, justification
 * mandatory — {@link ArchAllowlist}). The target is the fully qualified class name; for L1 additionally
 * the narrower form {@code <Klasse> -> <Zieltyp>}, so that an exception does not permit more than needed:
 * <pre>
 * layering-jsf-in-service     ch.plaintext.x.service.Foo -> org.primefaces.model.LazyDataModel  # ...
 * layering-backingbean-paket  ch.plaintext.x.service.BarBackingBean                             # ...
 * </pre>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextLayeringTest {

    /** Rule identifier of L1 in the allowlist. */
    static final String REGEL_L1 = "layering-jsf-in-service";

    /** Rule identifier of L2 in the allowlist. */
    static final String REGEL_L2 = "layering-backingbean-paket";

    /** Directory per module from which the classes of the reactor are read. */
    private static final String KLASSEN_SUFFIX = "target/classes";

    /** Only our own code — foreign classes in a {@code target/classes} (shading) are none of our business. */
    private static final String EIGENES_BASISPAKET = "ch.plaintext.";

    /** Package segments that make a class part of the service layer (L1). */
    private static final Set<String> DIENST_SEGMENTE = Set.of("service", "services");

    /** Package segments in which a backing bean has no business (L2). */
    private static final Set<String> UNTERE_SCHICHT_SEGMENTE =
            Set.of("service", "services", "repository", "repositories", "jpa");

    /**
     * Package segments that name the web layer. If one of them appears in the package, the backing
     * bean is registered where it belongs — that invalidates a hit from
     * {@link #UNTERE_SCHICHT_SEGMENTE} that only comes from the module namespace.
     */
    private static final Set<String> WEB_SEGMENTE =
            Set.of("web", "view", "views", "ui", "gui", "jsf", "bean", "beans");

    /**
     * Namespaces that bind a service to a running JSF request (L1). {@code
     * jakarta.servlet..} is deliberately not among them — see the class Javadoc.
     */
    private static final List<String> JSF_NAMENSRAEUME = List.of("jakarta.faces.", "org.primefaces.");

    private static final String SUFFIX_BACKING_BEAN = "BackingBean";

    private static final JavaClasses KLASSEN = importiereReactorKlassen();

    /**
     * Positive control. Both main tests are "nothing found" tests; without proof that any classes are
     * in view at all they would be green as soon as the import grasps at nothing (missing
     * {@code target/classes}, moved reactor root). Exactly this failure case has already made the
     * session bean test ineffective once.
     */
    @Test
    @DisplayName("Positivkontrolle: der Layering-Scan sieht Klassen des Reactors")
    void derTestSiehtEtwas() {
        assertFalse(KLASSEN.isEmpty(),
                () -> "Keine kompilierte ch.plaintext-Klasse gefunden — weder unter '" + KLASSEN_SUFFIX
                        + "' noch in einem Jar unter target/ — im Reactor "
                        + ReactorLayout.repoRoot() + " gefunden — der Import greift ins Leere und ein "
                        + "gruener Layering-Test wuerde nichts bedeuten. Zuerst 'mvn install' ueber den "
                        + "Reactor laufen lassen.");
    }

    @Test
    @DisplayName("L1: kein JSF/PrimeFaces in Klassen der Dienst-Schicht")
    void dienstschichtOhneJsf() {
        ArchAllowlist allowlist = ArchAllowlist.fuer(REGEL_L1);
        List<String> verstoesse = new ArrayList<>(allowlist.fehler());

        for (JavaClass klasse : KLASSEN) {
            if (!hatSegment(klasse.getPackageName(), DIENST_SEGMENTE)) {
                continue;
            }
            Set<String> ziele = new TreeSet<>();
            for (Dependency abhaengigkeit : klasse.getDirectDependenciesFromSelf()) {
                String ziel = abhaengigkeit.getTargetClass().getName();
                if (istJsfTyp(ziel) && !allowlist.erlaubt(klasse.getName() + " -> " + ziel)) {
                    ziele.add(ziel);
                }
            }
            if (!ziele.isEmpty() && !allowlist.erlaubt(klasse.getName())) {
                verstoesse.add(klasse.getName() + " -> " + String.join(", ", ziele));
            }
        }

        assertTrue(verstoesse.isEmpty(),
                () -> "\n\n=== L1: JSF/PrimeFaces in der Dienst-Schicht (%d) ===\n  ".formatted(verstoesse.size())
                        + String.join("\n  ", verstoesse.stream().sorted().toList())
                        + "\n\nEin Dienst muss ohne JSF-Request aufrufbar bleiben (Cron, REST, Test).\n"
                        + "JSF-Typen gehoeren in die Backing-Bean; der Dienst liefert einen Wert zurueck\n"
                        + "oder wirft eine Exception, statt eine FacesMessage zu setzen.\n"
                        + "Begruendete Ausnahme: '" + REGEL_L1 + " <Klasse>[ -> <Zieltyp>]  # <Grund>' in "
                        + ArchAllowlist.DATEINAME + ".\n");
    }

    @Test
    @DisplayName("L2: keine Backing-Bean im Dienst-, Repository- oder JPA-Paket")
    void backingBeansNichtInUnterenSchichten() {
        ArchAllowlist allowlist = ArchAllowlist.fuer(REGEL_L2);
        List<String> verstoesse = new ArrayList<>(allowlist.fehler());

        for (JavaClass klasse : KLASSEN) {
            if (klasse.getSimpleName().endsWith(SUFFIX_BACKING_BEAN)
                    && liegtInUntererSchicht(klasse.getPackageName())
                    && !allowlist.erlaubt(klasse.getName())) {
                verstoesse.add(klasse.getName());
            }
        }

        assertTrue(verstoesse.isEmpty(),
                () -> "\n\n=== L2: Backing-Beans in der falschen Schicht (%d) ===\n  ".formatted(verstoesse.size())
                        + String.join("\n  ", verstoesse.stream().sorted().toList())
                        + "\n\nEine Backing-Bean ist Web-Schicht und gehoert in ein '…web'- bzw. '…ui'-Paket.\n"
                        + "Liegt sie im Dienst-/Persistenzpaket, ist die Grenze nicht verschoben, sondern\n"
                        + "nie gezogen worden — der Zustand der Bean wandert dann in den Dienst.\n"
                        + "Begruendete Ausnahme: '" + REGEL_L2 + " <Klasse>  # <Grund>' in "
                        + ArchAllowlist.DATEINAME + ".\n");
    }

    /**
     * Unit test of the rule, and it is no accessory: both criteria check package <em>segments</em>,
     * not substrings, and L2 additionally hangs off the web exemption. Without this test it would
     * go unnoticed if {@code service} also matched {@code serviceable}, or if the web exemption
     * became so wide that it swallowed genuine hits.
     */
    @Test
    @DisplayName("Kriterien treffen Paketsegmente und Namensraeume genau")
    void kriterienTreffenNurEchteTreffer() {
        assertTrue(hatSegment("ch.plaintext.x.service", DIENST_SEGMENTE));
        assertTrue(hatSegment("ch.plaintext.x.service.impl", DIENST_SEGMENTE));
        assertTrue(hatSegment("ch.plaintext.x.services.mail", DIENST_SEGMENTE));
        assertFalse(hatSegment("ch.plaintext.x.serviceable", DIENST_SEGMENTE));
        assertFalse(hatSegment("ch.plaintext.x.web", DIENST_SEGMENTE));

        // L2: a hit where no web layer is named …
        assertTrue(liegtInUntererSchicht("ch.plaintext.schuetu.service"));
        assertTrue(liegtInUntererSchicht("ch.plaintext.schuetu.service.mqtt"));
        assertTrue(liegtInUntererSchicht("ch.plaintext.x.repository"));
        assertTrue(liegtInUntererSchicht("ch.plaintext.x.jpa.model"));
        // … and no hit where the module namespace supplies the segment but the bean lies in .web.
        assertFalse(liegtInUntererSchicht("ch.plaintext.jpa.web"));
        assertFalse(liegtInUntererSchicht("ch.plaintext.x.service.ui"));
        assertFalse(liegtInUntererSchicht("ch.plaintext.x.web"));
        assertFalse(liegtInUntererSchicht("ch.plaintext.x.jpaquery"));

        assertTrue(istJsfTyp("jakarta.faces.context.FacesContext"));
        assertTrue(istJsfTyp("org.primefaces.model.LazyDataModel"));
        assertFalse(istJsfTyp("jakarta.persistence.EntityManager"));
        assertFalse(istJsfTyp("org.primefacesX.Foo"));
        // Deliberately NO hit: the servlet API binds no service to a JSF request.
        assertFalse(istJsfTyp("jakarta.servlet.http.HttpSession"));
    }

    /** L2 criterion: lower layer named, web layer not. */
    private static boolean liegtInUntererSchicht(String paket) {
        return hatSegment(paket, UNTERE_SCHICHT_SEGMENTE) && !hatSegment(paket, WEB_SEGMENTE);
    }

    /** Does {@code paket} match one of the {@code segmente} as a complete package segment? */
    private static boolean hatSegment(String paket, Set<String> segmente) {
        for (String segment : paket.split("\\.")) {
            if (segmente.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean istJsfTyp(String klassenname) {
        return JSF_NAMENSRAEUME.stream().anyMatch(klassenname::startsWith);
    }

    /**
     * Classes of all modules of the reactor that are already compiled — without test classes and
     * without foreign namespaces. If {@code target/classes} is missing everywhere, the set stays
     * empty and {@link #derTestSiehtEtwas()} trips.
     */
    /**
     * For every module whose {@code target/classes} is missing, adds the built jar from
     * {@code target/}.
     *
     * <p><b>Why this is needed (30.08.2026, schuetu PR #213).</b> The consumer builds run with
     * the {@code maven-build-cache-extension}. On a cache hit it reports
     * "Found cached build, restoring …" and skips the plugin executions — the module jar
     * is restored from the cache into {@code target/}, but no {@code target/classes} is
     * created in the process. The import then grasped at nothing and the positive control
     * {@link #derTestSiehtEtwas()} failed even though nothing was wrong with the code: a PR that
     * only touches CSV files hits the cache in more modules than one that changes Java — which is
     * why the same branch was sometimes red and sometimes green.
     *
     * <p>The positive control thereby stays sharp: if <em>neither</em> the class directory
     * <em>nor</em> the jar is there, something really is broken and the test reports it.
     */
    private static List<Path> jarsOhneKlassenverzeichnis(List<Path> klassenverzeichnisse) {
        List<Path> jars = new ArrayList<>();
        Path repoRoot = ReactorLayout.repoRoot();
        if (repoRoot == null) {
            return jars;
        }
        try (Stream<Path> module = Files.list(repoRoot)) {
            for (Path modul : module.filter(Files::isDirectory).sorted().toList()) {
                Path klassen = modul.resolve(KLASSEN_SUFFIX);
                if (klassenverzeichnisse.contains(klassen) || !Files.isDirectory(modul.resolve("target"))) {
                    continue;
                }
                try (Stream<Path> inhalt = Files.list(modul.resolve("target"))) {
                    inhalt.filter(pfad -> {
                        String name = pfad.getFileName().toString();
                        return name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar")
                                && !name.endsWith("-exec.jar")
                                && !name.endsWith(".jar.original");
                    }).findFirst().ifPresent(jars::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Module unter " + repoRoot + " nicht lesbar", e);
        }
        return jars;
    }

    private static JavaClasses importiereReactorKlassen() {
        List<Path> roots = new ArrayList<>(ReactorLayout.sourceRoots(KLASSEN_SUFFIX));
        roots.addAll(jarsOhneKlassenverzeichnis(roots));
        JavaClasses alle = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(roots);
        return alle.that(new DescribedPredicate<>("eigener Code") {
            @Override
            public boolean test(JavaClass klasse) {
                return klasse.getName().startsWith(EIGENES_BASISPAKET);
            }
        });
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code plaintext-root-common} is a declared base, not an open one (card 1300, finding 7 of the
 * refactoring analysis 1274): a foreign repository may use exactly the types that carry
 * {@link StabileApi}; every other root-common type is root-internal.
 *
 * <p><b>Why.</b> {@code plaintext-root-common} is the real API surface of root —
 * {@code plaintext-root-interfaces} is only the official one. Measured on 23.09.2026 in bytecode
 * (main code of the five foreign repositories): 595 edges into root-common against 456 into
 * root-interfaces, spread over 22 of its 70 types. {@code SuperModel} alone is used by 204
 * classes in all five. A change to such a type is a six-repository event, and until now no
 * module name and no rule said so. This rule makes the surface a list: the 16 types declared
 * with {@link StabileApi} are API, the rest may not be reached from outside without a
 * justified allowlist entry.
 *
 * <p><b>Measured at the bytecode, not at import lines.</b> Card 1300 counted {@code import}
 * statements; an access by fully qualified name or from the same (split) package has none and
 * was missed. ArchUnit sees every direct dependency (field, parameter, call, annotation,
 * inheritance, class literal). What it does NOT see either: XHTML EL ({@code #{bean.x}}),
 * reflection, {@code AutoConfiguration.imports}, component scan and {@code persistence.xml}
 * — coupling through those channels stays outside this rule.
 *
 * <p><b>Where it applies.</b> Only to classes of the running reactor whose target lives in the
 * module {@code plaintext-root-common} <em>outside</em> this reactor — i.e. in the five
 * consumers. In root itself root-common is part of the reactor and every root module may use
 * it; there the rule only checks the declaration ({@link #deklarationIstLesbar()}). Test code
 * is not scanned (same import as {@link PlaintextLayeringTest}).
 *
 * <p><b>Allowlist.</b> Rule {@value #REGEL}, target {@code <Quellklasse> -> <Zielklasse>}
 * (top-level classes), justification mandatory. An entry that no longer matches any edge is
 * red too, so the list shrinks with the code ({@link ArchAllowlist#unbenutzt}).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextRootCommonApiTest {

    /** Rule identifier in the allowlist. */
    static final String REGEL = "root-common-api";

    /** The module this rule guards. */
    static final String ROOT_COMMON = "plaintext-root-common";

    /**
     * Lower bound for the declaration: the four hot spots of finding 7 at least. If fewer are
     * read, the lookup of root-common failed and every use would look undeclared — or, worse,
     * the set came back empty in a reactor without edges and the rule was green on nothing.
     */
    static final Set<String> MINDESTENS_DEKLARIERT = Set.of(
            "ch.plaintext.framework.SuperModel",
            "ch.plaintext.framework.PlaintextRepository",
            "ch.plaintext.boot.plugins.security.PlaintextSecurityHolder",
            "ch.plaintext.boot.plugins.jsf.FacesMessages");

    private static final String EIGENES_BASISPAKET = "ch.plaintext.";

    private static final JavaClasses KLASSEN = PlaintextLayeringTest.importiereReactorKlassen();

    @Test
    @DisplayName("Die Deklaration der stabilen root-common-Typen ist lesbar und enthaelt die vier Hot-Spots")
    void deklarationIstLesbar() {
        Set<String> stabil = stabileTypen();
        assertTrue(stabil.containsAll(MINDESTENS_DEKLARIERT),
                () -> "Aus " + ROOT_COMMON + " wurden " + stabil.size() + " @StabileApi-Typen gelesen: " + stabil
                        + "\nErwartet mindestens " + MINDESTENS_DEKLARIERT + ". Ohne die Liste waere jede Nutzung "
                        + "ein Verstoss — oder die Regel gruen, ohne etwas gewusst zu haben.");
    }

    @Test
    @DisplayName("Fremd-Repos nutzen aus plaintext-root-common nur als @StabileApi deklarierte Typen")
    void nurDeklarierteTypenAusRootCommon() {
        assertTrue(KLASSEN.size() >= 40,
                () -> "Der Scan sieht nur " + KLASSEN.size() + " Klassen — zuerst 'mvn verify' ueber den Reactor.");
        ArchAllowlist allowlist = ArchAllowlist.fuer(REGEL);
        List<String> verstoesse = new ArrayList<>(allowlist.fehler());
        for (String k : kanten(KLASSEN, stabileTypen())) {
            if (!allowlist.erlaubt(k)) {
                verstoesse.add(k);
            }
        }
        assertTrue(verstoesse.isEmpty(),
                () -> "\n\n=== root-common: nicht deklarierter Typ aus einem Fremd-Repo benutzt (%d) ===\n  "
                        .formatted(verstoesse.size())
                        + String.join("\n  ", verstoesse.stream().sorted().toList())
                        + "\n\nplaintext-root-common ist eine deklarierte Basis: nur Typen mit @StabileApi sind API\n"
                        + "fuer app/guild/schuetu/iot/fwtool, alles andere ist root-intern und darf sich ohne\n"
                        + "Vorwarnung aendern. Loesung: einen deklarierten Typ oder plaintext-root-interfaces\n"
                        + "benutzen, oder den Typ in root mit Begruendung als @StabileApi deklarieren.\n"
                        + "Begruendete Ausnahme: '" + REGEL + " <Quellklasse> -> <Zielklasse>  # <Karte, Grund>' in "
                        + ArchAllowlist.DATEINAME + ".\n");
    }

    @Test
    @DisplayName("Jeder root-common-api-Eintrag der Allowlist trifft noch eine bestehende Kante")
    void keineVerwaistenAusnahmen() {
        List<String> verwaist = ArchAllowlist.fuer(REGEL).unbenutzt(kanten(KLASSEN, stabileTypen()));
        assertTrue(verwaist.isEmpty(),
                () -> "\n\nDiese '" + REGEL + "'-Ausnahmen treffen keine Kante mehr — Zeile aus "
                        + ArchAllowlist.DATEINAME + " loeschen:\n  " + String.join("\n  ", verwaist) + "\n");
    }

    @Test
    @DisplayName("Positivkontrolle: nur fremde, nicht deklarierte root-common-Ziele sind Kanten")
    void positivkontrolle() {
        Set<String> stabil = Set.of("ch.plaintext.framework.SuperModel");
        assertEquals("ch.plaintext.x.A -> ch.plaintext.boot.utils.TimeUtil",
                kante("ch.plaintext.x.A$1", "ch.plaintext.boot.utils.TimeUtil", ROOT_COMMON, false, stabil));
        assertNull(kante("ch.plaintext.x.A", "ch.plaintext.framework.SuperModel", ROOT_COMMON, false, stabil),
                "deklariert");
        assertNull(kante("ch.plaintext.x.A", "ch.plaintext.framework.SuperModel$Inner", ROOT_COMMON, false, stabil),
                "verschachtelt in deklariertem Typ");
        assertNull(kante("ch.plaintext.x.A", "ch.plaintext.boot.utils.TimeUtil", ROOT_COMMON, true, stabil),
                "root-common im eigenen Reactor (root selbst)");
        assertNull(kante("ch.plaintext.x.A", "ch.plaintext.I", "plaintext-root-interfaces", false, stabil),
                "anderes Modul");
        assertFalse(stabileTypen().isEmpty(), "die echte Deklaration wird gelesen");
    }

    // ------------------------------------------------------------------ Mechanics

    /** Edges {@code "<Quellklasse> -> <Zielklasse>"} of the reactor into non-declared root-common types. */
    static Set<String> kanten(JavaClasses klassen, Set<String> stabil) {
        Set<String> reaktor = new TreeSet<>();
        klassen.forEach(k -> reaktor.add(k.getName()));
        ClassLoader loader = PlaintextRootCommonApiTest.class.getClassLoader();
        Map<String, String> cache = new HashMap<>();
        Set<String> kanten = new TreeSet<>();
        for (JavaClass klasse : klassen) {
            for (Dependency d : klasse.getDirectDependenciesFromSelf()) {
                String ziel = d.getTargetClass().getBaseComponentType().getName();
                if (!ziel.startsWith(EIGENES_BASISPAKET)) {
                    continue;
                }
                boolean imReaktor = reaktor.contains(ziel);
                String modul = imReaktor ? null
                        : cache.computeIfAbsent(ziel, z -> String.valueOf(PlaintextModulgrenzenTest.modulUeberClassLoader(loader, z)));
                String k = kante(klasse.getName(), ziel, modul, imReaktor, stabil);
                if (k != null) {
                    kanten.add(k);
                }
            }
        }
        return kanten;
    }

    static String kante(String quelle, String ziel, String zielModul, boolean zielImReaktor, Set<String> stabil) {
        if (zielImReaktor || !ROOT_COMMON.equals(zielModul) || stabil.contains(aussen(ziel))) {
            return null;
        }
        return aussen(quelle) + " -> " + aussen(ziel);
    }

    /**
     * The types of root-common annotated with {@link StabileApi}, read from wherever root-common
     * sits on this classpath (its jar in a consumer, its {@code target/classes} in root).
     */
    static Set<String> stabileTypen() {
        URL url = PlaintextRootCommonApiTest.class.getClassLoader()
                .getResource(StabileApi.class.getName().replace('.', '/') + ".class");
        assertNotNull(url, "StabileApi liegt nicht im Classpath — plaintext-root-common fehlt");
        JavaClasses common = new ClassFileImporter().importLocations(
                List.of(com.tngtech.archunit.core.importer.Location.of(wurzelVon(url))));
        Set<String> stabil = new TreeSet<>();
        for (JavaClass k : common) {
            if (k.isAnnotatedWith(StabileApi.class)) {
                stabil.add(k.getName());
            }
        }
        return stabil;
    }

    /** The jar or class directory that contains {@code url}. */
    private static java.net.URI wurzelVon(URL url) {
        String s = url.toString();
        try {
            if (s.startsWith("jar:")) {
                return new java.net.URI(s.substring(0, s.indexOf("!/") + 2));
            }
            Path datei = Path.of(url.toURI());
            int tiefe = StabileApi.class.getName().split("\\.").length;
            Path wurzel = datei;
            for (int i = 0; i < tiefe; i++) {
                wurzel = wurzel.getParent();
            }
            return wurzel.toUri();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Fundort von StabileApi nicht lesbar: " + s, e);
        }
    }

    private static String aussen(String klasse) {
        int i = klasse.indexOf('$');
        return i < 0 ? klasse : klasse.substring(0, i);
    }
}

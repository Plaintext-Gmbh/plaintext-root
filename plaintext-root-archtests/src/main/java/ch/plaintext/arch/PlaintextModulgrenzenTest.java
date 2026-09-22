/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first rule that guards a module boundary (card 1299, finding 5 of the refactoring
 * analysis 1274): no specialist module reaches into another specialist module except through an
 * {@code …-interfaces} module.
 *
 * <p><b>Why now, and why with a frozen list.</b> Until 22.09.2026 not a single rule in the six
 * repositories checked a module or package boundary; {@link PlaintextLayeringTest} records why a
 * cycle rule was dropped (31 cycle groups in root — "a rule with thirty exceptions checks
 * nothing, it only documents"). That is true for a rule that is meant to be <em>fulfilled</em>.
 * This rule is meant to <em>freeze</em>: every edge that exists today stands in the reactor's
 * allowlist with a justification and a card number, and everything new is red. The findings 6 to
 * 9 of analysis 1274 all came about in a green build — guild reaching past
 * {@code plaintext-app-interfaces} into app implementation modules (card 1297) is the case that
 * had already happened. Resolving the existing edges is a stage of its own; this rule only makes
 * sure the list shrinks and does not grow.
 *
 * <p><b>What is measured: bytecode, not import lines.</b> Every direct dependency ArchUnit sees
 * (field, parameter, return type, call, annotation, inheritance, fully qualified reference
 * without an import) of a class of this reactor on a {@code ch.plaintext} class of a
 * <em>different Maven module</em>. The module of a class is where its bytes come from: the
 * directory in front of {@code /target/} for the reactor's own modules, the artifact directory
 * of the Maven repository layout for a jar. That is why the rule also sees edges into other
 * repositories (guild &rarr; app, app &rarr; root) — they arrive as jars on the test classpath.
 *
 * <p><b>What is allowed without an entry.</b>
 * <ul>
 *   <li>Targets in a module whose name ends with {@code -interfaces} — the declared contracts.</li>
 *   <li>Targets in a <em>base module</em> ({@link #istBasis(String)}): the platform layer of
 *       root ({@link #ROOT_BASIS}) and a repository's own {@code …-common} module. They are the
 *       floor every specialist module stands on; which of their types a foreign repository may
 *       use is a different question (card 1300).</li>
 *   <li>Sources in a {@code …-webapp} module: it is the composition root that assembles the
 *       modules, and reaching into them is its job.</li>
 * </ul>
 * Everything else is a specialist module: root's {@code plaintext-admin-*}, the remaining
 * {@code plaintext-root-*} feature modules, every {@code plaintext-z-*}, {@code plaintext-gear},
 * guild's {@code plaintext-guild-<fach>}. An edge between two of them is a violation.
 *
 * <p><b>Why modules and not top-level packages.</b> The wording in card 1299 was "no
 * {@code ch.plaintext.<fach>} imports another {@code ch.plaintext.<fach>}". The package is the
 * wrong unit here: guild keeps all six specialist modules under {@code ch.plaintext.guild},
 * and {@code ch.plaintext.boot} is spread across seven modules (card 1274 counts 32 split
 * packages). A package rule would be blind exactly where the damage is. The Maven module is the
 * unit a POM declares and a build breaks on.
 *
 * <p><b>Allowlist.</b> Rule {@value #REGEL}, target {@code <Quellklasse> -> <Zielklasse>} (top-level
 * classes; nested and anonymous classes count for their enclosing class), glob {@code *} allowed,
 * justification mandatory ({@link ArchAllowlist}):
 * <pre>
 * modulgrenze  ch.plaintext.guild.member.web.MemberBackingBean -> ch.plaintext.kontakte.Kontakt  # card 1297 ...
 * </pre>
 * Frozen on 23.09.2026: root 0 edges (no allowlist, as for every shared rule), app 14 (all into
 * root's {@code plaintext-root-watch} and {@code plaintext-admin-apitoken}), guild 12 (9 inside
 * guild, 3 into app — card 1297), schuetu 2 (into {@code plaintext-admin-apitoken}), iot and
 * fwtool 0. Cross-checked with {@code jdeps}: the same edges, plus one in schuetu that ArchUnit
 * cannot see — a read of a compile-time constant ({@code static final}), which javac inlines;
 * only a constant-pool entry remains. That is the one kind of source dependency this rule misses.
 *
 * <p>An entry that no longer matches any edge makes the test red as well: the list may only shrink
 * together with the code, otherwise it would permit the edge again the next time somebody
 * writes it.
 *
 * <p><b>Lower bound.</b> The scan must see at least {@value #MINDESTENS_KLASSEN} classes and
 * {@value #MINDESTENS_MODULE} modules — a boundary rule that sees nothing reports "no boundary
 * violated". Measured on 22./23.09.2026 (see the constants).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextModulgrenzenTest {

    /** Rule identifier in the allowlist. */
    static final String REGEL = "modulgrenze";

    /** Only our own code is judged, and only our own code counts as a target. */
    private static final String EIGENES_BASISPAKET = "ch.plaintext.";

    /**
     * root's platform layer: modules every application stands on and that carry no specialist
     * logic. Named explicitly instead of by pattern, because {@code plaintext-root-watch},
     * {@code -role-assignment} and {@code -menu-visibility} carry the same prefix and ARE
     * specialist modules.
     */
    static final Set<String> ROOT_BASIS = Set.of(
            "plaintext-root-common",
            "plaintext-root-jpa",
            "plaintext-root-web",
            "plaintext-root-menu",
            "plaintext-root-flyway",
            "plaintext-root-pageguard",
            "plaintext-root-webapp");

    /**
     * Lower bounds of the scan set, measured on 22./23.09.2026 on the six reactors (classes /
     * modules with {@code ch.plaintext} classes): root 567 / 23 (webapp run), app 1172 / 30,
     * guild 301 / 8, schuetu 267 / 1, iot 51 / 2, fwtool 54 / 5. The bound lies below the
     * smallest of the six, because the same jar runs in all of them. schuetu has a single module
     * with such classes (its webapp's main class lives in package {@code ch}), so the module
     * bound is 1 — the class bound is what catches an empty scan.
     */
    private static final int MINDESTENS_KLASSEN = 40;

    private static final int MINDESTENS_MODULE = 1;

    private static final JavaClasses KLASSEN = PlaintextLayeringTest.importiereReactorKlassen();

    @Test
    @DisplayName("Kein Fachmodul greift an einem -interfaces-Modul vorbei in ein anderes Fachmodul")
    void keinFachmodulGreiftInEinAnderes() {
        Map<String, String> modulJeKlasse = modulJeKlasse(KLASSEN);
        Set<String> module = new TreeSet<>(modulJeKlasse.values());
        assertTrue(KLASSEN.size() >= MINDESTENS_KLASSEN && module.size() >= MINDESTENS_MODULE,
                () -> "Der Modulgrenzen-Scan sieht nur " + KLASSEN.size() + " Klassen in " + module.size()
                        + " Modulen (" + module + "), festgehalten sind mindestens " + MINDESTENS_KLASSEN
                        + " / " + MINDESTENS_MODULE + ". Eine Grenzregel, die nichts sieht, meldet 'keine "
                        + "Grenze verletzt' — zuerst 'mvn verify' ueber den Reactor laufen lassen.");

        ArchAllowlist allowlist = ArchAllowlist.fuer(REGEL);
        Map<String, Set<String>> kanten = kanten(KLASSEN, modulJeKlasse);

        List<String> verstoesse = new ArrayList<>(allowlist.fehler());
        for (Map.Entry<String, Set<String>> e : kanten.entrySet()) {
            for (String kante : e.getValue()) {
                if (!allowlist.erlaubt(kante)) {
                    verstoesse.add(kante + "   [" + e.getKey() + "]");
                }
            }
        }
        schreibeBericht(kanten, modulJeKlasse);

        assertTrue(verstoesse.isEmpty(),
                () -> "\n\n=== Modulgrenze: Fachmodul greift in ein anderes Fachmodul (%d) ===\n  "
                        .formatted(verstoesse.size())
                        + String.join("\n  ", verstoesse.stream().sorted().toList())
                        + "\n\nEin Fachmodul spricht mit einem anderen nur ueber dessen -interfaces-Modul.\n"
                        + "Loesung: den benutzten Typ (oder ein Interface dafuer) ins -interfaces-Modul heben\n"
                        + "und dagegen programmieren. Begruendete Ausnahme (Ist-Zustand einfrieren, nicht\n"
                        + "Neues erlauben): '" + REGEL + " <Quellklasse> -> <Zielklasse>  # <Karte, Grund>' in "
                        + ArchAllowlist.DATEINAME + ".\n");
    }

    /**
     * Without this test the allowlist could only grow: an entry whose edge was removed would stay
     * and permit the edge again the next time somebody writes it. Glob entries count as used if
     * they match at least one edge.
     */
    @Test
    @DisplayName("Jeder modulgrenze-Eintrag der Allowlist trifft noch eine bestehende Kante")
    void keineVerwaistenAusnahmen() {
        Map<String, Set<String>> kanten = kanten(KLASSEN, modulJeKlasse(KLASSEN));
        Set<String> alle = new TreeSet<>();
        kanten.values().forEach(alle::addAll);
        List<String> verwaist = ArchAllowlist.fuer(REGEL).unbenutzt(alle);
        assertTrue(verwaist.isEmpty(),
                () -> "\n\nDiese '" + REGEL + "'-Ausnahmen treffen keine Kante mehr — die Grenze ist an dieser\n"
                        + "Stelle eingehalten. Zeile aus " + ArchAllowlist.DATEINAME + " loeschen, damit die\n"
                        + "Liste mit dem Code schrumpft:\n  " + String.join("\n  ", verwaist) + "\n");
    }

    /**
     * Unit test of the classification. The rule stands or falls with two mechanical decisions — which
     * module a class comes from, and which modules are contracts or base — and both would fail
     * silently: a module name that is read wrongly makes every edge an internal one.
     */
    @Test
    @DisplayName("Positivkontrolle: Modulherkunft und Einstufung treffen genau")
    void positivkontrolle() {
        assertEquals("plaintext-z-kontakte",
                modulAusQuelle("file:/w/plaintext-app/plaintext-z-kontakte/target/classes/ch/plaintext/kontakte/K.class"));
        assertEquals("plaintext-guild-member",
                modulAusQuelle("jar:file:/w/plaintext-guild/plaintext-guild-member/target/plaintext-guild-member-1.0.jar!/ch/x/A.class"));
        assertEquals("plaintext-z-rechnungen",
                modulAusQuelle("jar:file:/h/.m2/repository/ch/plaintext/plaintext-z-rechnungen/2.1845.0/"
                        + "plaintext-z-rechnungen-2.1845.0.jar!/ch/plaintext/rechnungen/R.class"));
        assertNull(modulAusQuelle("jrt:/java.base/java/lang/String.class"));

        assertTrue(istErlaubtesZiel("plaintext-app-interfaces"));
        assertTrue(istErlaubtesZiel("plaintext-root-interfaces"));
        assertTrue(istErlaubtesZiel("plaintext-root-common"));
        assertTrue(istErlaubtesZiel("plaintext-guild-common"));
        assertFalse(istErlaubtesZiel("plaintext-z-kontakte"), "Fachmodul als Ziel");
        assertFalse(istErlaubtesZiel("plaintext-admin-settings"), "root-Fachmodul als Ziel");
        assertFalse(istErlaubtesZiel("plaintext-root-watch"), "root-Praefix macht noch keine Basis");
        assertFalse(istErlaubtesZiel("plaintext-interfaces-helper"), "nur das Suffix zaehlt");

        // End to end on the decision the rule makes for one edge.
        assertEquals("ch.plaintext.guild.member.web.MemberBackingBean -> ch.plaintext.kontakte.model.Kontakt",
                kante("ch.plaintext.guild.member.web.MemberBackingBean$1", "plaintext-guild-member",
                        "ch.plaintext.kontakte.model.Kontakt", "plaintext-z-kontakte"));
        assertNull(kante("ch.plaintext.a.A", "plaintext-z-a", "ch.plaintext.a.B", "plaintext-z-a"), "gleiches Modul");
        assertNull(kante("ch.plaintext.a.A", "plaintext-z-a", "ch.plaintext.i.I", "plaintext-app-interfaces"), "Vertrag");
        assertNull(kante("ch.plaintext.boot.X", "plaintext-app-webapp", "ch.plaintext.b.B", "plaintext-z-b"),
                "Kompositionswurzel");
        assertNull(kante("ch.plaintext.a.A", "plaintext-z-a", "ch.plaintext.b.B", null), "Herkunft unbekannt");
    }

    // ------------------------------------------------------------------ Mechanics

    /** Edges per module pair {@code "quelle -> ziel"}, each as {@code "<Quellklasse> -> <Zielklasse>"}. */
    static Map<String, Set<String>> kanten(JavaClasses klassen, Map<String, String> modulJeKlasse) {
        ClassLoader loader = PlaintextModulgrenzenTest.class.getClassLoader();
        Map<String, String> fremdCache = new HashMap<>();
        Map<String, Set<String>> kanten = new TreeMap<>();
        for (JavaClass klasse : klassen) {
            String quellModul = modulJeKlasse.get(klasse.getName());
            for (Dependency d : klasse.getDirectDependenciesFromSelf()) {
                String ziel = d.getTargetClass().getBaseComponentType().getName();
                if (!ziel.startsWith(EIGENES_BASISPAKET)) {
                    continue;
                }
                String zielModul = modulJeKlasse.containsKey(ziel)
                        ? modulJeKlasse.get(ziel)
                        : fremdCache.computeIfAbsent(ziel, z -> modulUeberClassLoader(loader, z));
                String k = kante(klasse.getName(), quellModul, ziel, zielModul);
                if (k != null) {
                    kanten.computeIfAbsent(quellModul + " -> " + zielModul, x -> new TreeSet<>()).add(k);
                }
            }
        }
        return kanten;
    }

    /** The edge as the allowlist names it, or {@code null} if the dependency is allowed by construction. */
    static String kante(String quelle, String quellModul, String ziel, String zielModul) {
        if (quellModul == null || zielModul == null || quellModul.equals(zielModul)
                || quellModul.endsWith("-webapp") || istErlaubtesZiel(zielModul)) {
            return null;
        }
        return aussen(quelle) + " -> " + aussen(ziel);
    }

    static boolean istErlaubtesZiel(String modul) {
        return modul.endsWith("-interfaces") || istBasis(modul);
    }

    static boolean istBasis(String modul) {
        return ROOT_BASIS.contains(modul) || modul.endsWith("-common");
    }

    private static String aussen(String klasse) {
        int i = klasse.indexOf('$');
        return i < 0 ? klasse : klasse.substring(0, i);
    }

    private static Map<String, String> modulJeKlasse(JavaClasses klassen) {
        Map<String, String> m = new HashMap<>();
        for (JavaClass k : klassen) {
            k.getSource().ifPresent(s -> {
                String modul = modulAusQuelle(s.getUri().toString());
                if (modul != null) {
                    m.put(k.getName(), modul);
                }
            });
        }
        return m;
    }

    private static String modulUeberClassLoader(ClassLoader loader, String klasse) {
        URL url = loader.getResource(klasse.replace('.', '/') + ".class");
        return url == null ? null : modulAusQuelle(url.toString());
    }

    /**
     * Module name from the location of a class file: the directory in front of {@code /target/}
     * (reactor module, class directory or built jar), otherwise the artifact directory of the
     * Maven repository layout ({@code …/<artifactId>/<version>/<artifactId>-<version>.jar}).
     */
    static String modulAusQuelle(String uri) {
        String p = uri;
        if (p.startsWith("jar:")) {
            p = p.substring(4);
            int bang = p.indexOf("!/");
            if (bang >= 0) {
                p = p.substring(0, bang);
            }
        }
        if (!p.startsWith("file:")) {
            return null;
        }
        int t = p.lastIndexOf("/target/");
        if (t >= 0) {
            String vorn = p.substring(0, t);
            return vorn.substring(vorn.lastIndexOf('/') + 1);
        }
        if (p.endsWith(".jar")) {
            String[] teile = p.split("/");
            return teile.length >= 3 ? teile[teile.length - 3] : null;
        }
        return null;
    }

    /**
     * Writes the full edge list to {@code target/plaintext-modulgrenzen.txt} of the running module —
     * the figure the next stage measures against, and the source for allowlist entries.
     */
    private static void schreibeBericht(Map<String, Set<String>> kanten, Map<String, String> modulJeKlasse) {
        Path ziel = ReactorLayout.start().resolve("target").resolve("plaintext-modulgrenzen.txt");
        Map<String, Integer> klassenJeModul = new TreeMap<>();
        modulJeKlasse.values().forEach(m -> klassenJeModul.merge(m, 1, Integer::sum));
        StringBuilder sb = new StringBuilder();
        int summe = kanten.values().stream().mapToInt(Set::size).sum();
        sb.append("# Modulgrenzen-Kanten: ").append(summe).append(" Klassenkanten in ").append(kanten.size())
                .append(" Modulpaaren, ").append(KLASSEN.size()).append(" Klassen in ").append(klassenJeModul.size())
                .append(" Modulen\n# Gescannt: ").append(klassenJeModul).append('\n');
        kanten.forEach((paar, liste) -> {
            sb.append("\n## ").append(paar).append(" (").append(liste.size()).append(")\n");
            liste.forEach(k -> sb.append(REGEL).append("  ").append(k).append('\n'));
        });
        try {
            Files.createDirectories(ziel.getParent());
            Files.writeString(ziel, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Bericht nicht schreibbar: " + ziel, e);
        }
    }
}

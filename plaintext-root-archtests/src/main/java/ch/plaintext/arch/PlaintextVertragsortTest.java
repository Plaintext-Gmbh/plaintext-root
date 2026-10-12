/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a contract lives (card 1504, decision Daniel in card 1502): an interface that is implemented
 * in another module and not used in its own belongs to the implementing module — more precisely to
 * that provider's {@code <modul>-api} jar, which is released with the provider.
 *
 * <p><b>Why.</b> Card 1503 wants an empty shell that loads module jars at runtime. A shared
 * contract pot ({@code plaintext-app-interfaces}: 59 types of about 20 providers) cannot be loaded
 * piecewise — every consumer hangs on all providers' contracts at once. {@link PlaintextModulgrenzenTest}
 * even recommended the pot until card 1504 ("lift the type into the -interfaces module"). This rule
 * names the right place instead.
 *
 * <p><b>What is a violation.</b> Interface {@code I} in module {@code A} of this reactor, implemented by
 * a concrete class in a module {@code B} that is neither {@code A} nor {@code A}'s owner (for
 * {@code plaintext-z-x-api} the owner is {@code plaintext-z-x}), AND no class of {@code A} or its owner
 * uses {@code I} (field, parameter, call ...) — then {@code A} does not need {@code I}, only
 * {@code B} does, and {@code I} belongs to {@code B} — or, if a third module collects the
 * implementations ({@code List<I>}), to that module's {@code -api}. The rule reports where {@code I}
 * is implemented; which of the two applies is for the human to read. What stays allowed without an entry:
 * <ul>
 *   <li>an extension point: the module that defines {@code I} collects or calls it
 *       ({@code List<I>}, {@code ObjectProvider<I>});</li>
 *   <li>the shell: its API {@code plaintext-root-interfaces} ({@link #HUELLE}) — what the shell offers
 *       or collects, implemented by modules (pattern {@code SystemMailSender}) — and root's platform
 *       layer {@link PlaintextModulgrenzenTest#ROOT_BASIS}, whose base types every module implements
 *       ({@code XstreamStorable}, measured when the rule was introduced);</li>
 *   <li>an interface implemented only in its own module (or by its owner).</li>
 * </ul>
 * A use counts only from a class that is not itself an interface, a record or an implementation of
 * {@code I}: in a contract pot the contracts mention each other ({@code IKontaktService} returns
 * {@code IKontakt}) and would whitewash one another.
 *
 * <p><b>Measured in bytecode</b> over the reactor's main classes ({@link PlaintextLayeringTest#importiereReactorKlassen()}).
 * Implementations in another repository are not seen — {@code app-interfaces} is judged in app by
 * app's implementations; a guild implementation of an app interface is not counted.
 *
 * <p><b>Allowlist.</b> Rule {@value #REGEL}, target = the interface's fully qualified name,
 * justification mandatory. Frozen per reactor on introduction; an entry whose violation is gone is red
 * too, so the list only shrinks ({@link ArchAllowlist#unbenutzt}). root keeps no allowlist.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextVertragsortTest {

    /** Rule identifier in the allowlist. */
    static final String REGEL = "vertragsort";

    /** The shell's API: may hold contracts that modules implement. */
    static final String HUELLE = "plaintext-root-interfaces";

    private static final JavaClasses KLASSEN = PlaintextLayeringTest.importiereReactorKlassen();

    @Test
    @DisplayName("Ein Interface, das nur ein anderes Modul umsetzt, liegt im -api-Modul dieses Anbieters")
    void vertragLiegtBeimAnbieter() {
        assertTrue(KLASSEN.size() >= 40,
                () -> "Der Vertragsort-Scan sieht nur " + KLASSEN.size() + " Klassen — zuerst 'mvn verify' ueber den Reactor.");
        ArchAllowlist allowlist = ArchAllowlist.fuer(REGEL);
        List<String> verstoesse = new ArrayList<>(allowlist.fehler());
        verstoesse(KLASSEN).forEach((schnittstelle, wohin) -> {
            if (!allowlist.erlaubt(schnittstelle)) {
                verstoesse.add(REGEL + "  " + schnittstelle + "   # " + wohin);
            }
        });
        assertTrue(verstoesse.isEmpty(),
                () -> "\n\n=== Vertragsort: Interface liegt nicht beim Anbieter (%d) ===\n  ".formatted(verstoesse.size())
                        + String.join("\n  ", verstoesse)
                        + "\n\nDas Interface wird nur in einem anderen Modul umgesetzt und im eigenen nicht benutzt.\n"
                        + "Loesung: Vertrag eines Anbieters -> in dessen -api-Modul (<anbieter>-api, wird mit ihm\n"
                        + "released; Paket darf bleiben). Erweiterungspunkt, den ein Modul sammelt (List<I>,\n"
                        + "ObjectProvider<I>) -> ins -api-Modul des sammelnden Moduls. Kein gemeinsames\n"
                        + "-interfaces-Modul (Karte 1503/1504). Begruendete Ausnahme (Ist-Zustand einfrieren): '" + REGEL + " <Interface>  # <Karte, Grund>' in " + ArchAllowlist.DATEINAME + ".\n");
    }

    @Test
    @DisplayName("Jeder vertragsort-Eintrag der Allowlist trifft noch ein Interface am falschen Ort")
    void keineVerwaistenAusnahmen() {
        List<String> verwaist = ArchAllowlist.fuer(REGEL).unbenutzt(verstoesse(KLASSEN).keySet());
        assertTrue(verwaist.isEmpty(),
                () -> "\n\nDiese '" + REGEL + "'-Ausnahmen treffen kein Interface mehr — Zeile aus "
                        + ArchAllowlist.DATEINAME + " loeschen:\n  " + String.join("\n  ", verwaist) + "\n");
    }

    @Test
    @DisplayName("Positivkontrolle: Anbieter, Eigentuemer und Huelle werden richtig eingestuft")
    void positivkontrolle() {
        assertEquals("umgesetzt in plaintext-z-mailbox",
                urteil("plaintext-app-interfaces", Set.of("plaintext-z-mailbox"), false));
        assertEquals("umgesetzt in plaintext-guild-events, plaintext-guild-lists",
                urteil("plaintext-guild-common", Set.of("plaintext-guild-lists", "plaintext-guild-events"), false));
        assertNull(urteil("plaintext-app-interfaces", Set.of("plaintext-z-mailbox"), true), "Erweiterungspunkt");
        assertNull(urteil(HUELLE, Set.of("plaintext-z-mailbox"), false), "Huelle");
        assertNull(urteil("plaintext-root-common", Set.of("plaintext-z-a", "plaintext-z-b"), false), "Basistyp der Huelle");
        assertEquals("umgesetzt in plaintext-z-a",
                urteil("plaintext-root-watch", Set.of("plaintext-z-a"), false), "root-Fachmodul ist keine Huelle");
        assertNull(urteil("plaintext-z-mailbox-api", Set.of("plaintext-z-mailbox"), false), "-api beim Anbieter");
        assertNull(urteil("plaintext-z-a", Set.of("plaintext-z-a"), false), "nur im eigenen Modul umgesetzt");
        assertNull(urteil("plaintext-z-a", Set.of(), false), "nirgends umgesetzt");
        assertEquals("plaintext-z-x", eigentuemer("plaintext-z-x-api"));
        assertEquals("plaintext-z-x", eigentuemer("plaintext-z-x"));
    }

    // ------------------------------------------------------------------ Mechanics

    /** Interfaces at the wrong place, each with the modules that implement them. */
    static Map<String, String> verstoesse(JavaClasses klassen) {
        Map<String, String> modulJeKlasse = PlaintextModulgrenzenTest.modulJeKlasse(klassen);
        Map<String, String> ergebnis = new TreeMap<>();
        for (JavaClass i : klassen) {
            String heim = modulJeKlasse.get(i.getName());
            if (!i.isInterface() || i.isAnnotation() || heim == null) {
                continue;
            }
            Set<String> eigene = new TreeSet<>(List.of(heim, eigentuemer(heim)));
            Set<String> umsetzer = new TreeSet<>();
            for (JavaClass sub : i.getAllSubclasses()) {
                String m = modulJeKlasse.get(sub.getName());
                if (!sub.isInterface() && m != null) {
                    umsetzer.add(m);
                }
            }
            boolean benutzt = false;
            for (Dependency d : i.getDirectDependenciesToSelf()) {
                JavaClass von = d.getOriginClass();
                if (eigene.contains(modulJeKlasse.get(von.getName())) && !von.isInterface() && !von.isRecord()
                        && !von.isAssignableTo(i.getName())) {
                    benutzt = true;
                    break;
                }
            }
            String u = urteil(heim, umsetzer, benutzt);
            if (u != null) {
                ergebnis.put(i.getName(), u);
            }
        }
        return ergebnis;
    }

    /** {@code null} if the interface may stay in {@code heim}, otherwise the foreign modules implementing it. */
    static String urteil(String heim, Set<String> umsetzerModule, boolean imHeimBenutzt) {
        if (HUELLE.equals(heim) || PlaintextModulgrenzenTest.ROOT_BASIS.contains(heim) || imHeimBenutzt) {
            return null;
        }
        Set<String> fremde = new TreeSet<>(umsetzerModule);
        fremde.remove(heim);
        fremde.remove(eigentuemer(heim));
        if (fremde.isEmpty()) {
            return null;
        }
        return "umgesetzt in " + String.join(", ", fremde);
    }

    /** The provider an {@code -api} module belongs to; any other module owns itself. */
    static String eigentuemer(String modul) {
        return modul.endsWith("-api") ? modul.substring(0, modul.length() - "-api".length()) : modul;
    }
}

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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geteilte Layering-Leitplanke (Zustandsbericht 29.08.2026, Massnahme 12): die Web-Schicht darf
 * nach unten greifen, die Dienst-Schicht nicht nach oben.
 *
 * <p><b>Ausloeser.</b> In schuetu liegt die halbe Anwendung im Paket
 * {@code ch.plaintext.schuetu.service} — inklusive Backing-Beans und PrimeFaces-{@code
 * LazyDataModel}s. Das ist kein Schoenheitsfehler: eine Klasse, die {@code FacesContext} zieht, ist
 * ausserhalb eines JSF-Requests nicht mehr aufrufbar (kein Cron, kein REST, kein Test ohne
 * Faces-Mock), und sie zwingt jedes Modul, das den Dienst braucht, die gesamte JSF-Kette in den
 * Classpath zu nehmen. Der Schaden faellt erst auf, wenn jemand den Dienst wiederverwenden will —
 * also spaet.
 *
 * <p><b>Was geprueft wird.</b>
 * <ul>
 *   <li><b>L1</b> — eine Klasse in einem Paket mit dem Segment {@code service} oder {@code services}
 *       haengt nicht an {@code jakarta.faces..} oder {@code org.primefaces..} (Import, Feld,
 *       Parameter, Rueckgabe, Aufruf, Annotation — alles, was ArchUnit als Abhaengigkeit sieht).
 *       Regel-Kennung {@value #REGEL_L1}.</li>
 *   <li><b>L2</b> — eine Klasse mit dem Namenssuffix {@code BackingBean} liegt nicht in einem Paket
 *       mit dem Segment {@code service}, {@code services}, {@code repository}, {@code repositories}
 *       oder {@code jpa}, es sei denn, dasselbe Paket benennt auch die Web-Schicht (Segment
 *       {@code web}, {@code view(s)}, {@code ui}, {@code gui}, {@code jsf}, {@code bean(s)}).
 *       Regel-Kennung {@value #REGEL_L2}.</li>
 * </ul>
 *
 * <p><b>Warum {@code jakarta.servlet..} NICHT in L1 steht.</b> Der erste Zuschnitt hatte es dabei
 * und traf damit genau eine root-Klasse: {@code ch.plaintext.sessions.service.HttpSessionRegistry},
 * eine Registry lebender {@code jakarta.servlet.http.HttpSession}-Objekte. Deren Gegenstand
 * <em>ist</em> die Servlet-Session; ohne die Servlet-API gibt es die Klasse nicht. Die Wahl stand
 * zwischen einer dauerhaften Ausnahme fuer eine sinnvolle Klasse und einem ehrlicheren Zuschnitt —
 * und der Befund des Berichts ist die JSF-Kopplung, nicht die Servlet-API. Ein Servlet-Filter oder
 * eine Session-Registry ist Web-Infrastruktur, aber sie zwingt niemandem JSF auf und laeuft im
 * Test ohne Faces-Kontext. Bewusst in Kauf genommen: ein Servlet-Filter, der im
 * {@code service}-Paket liegt (schuetu hat zwei), faellt hier nicht mehr auf. Das ist eine Frage
 * der Paketbenennung, nicht der Schichtung.
 *
 * <p><b>Warum L2 ein Web-Segment im selben Paket durchgehen laesst.</b> Der Modulname darf nicht
 * gegen die Klasse zaehlen. {@code ch.plaintext.jpa.web.AdminEntityBackingBean} traegt das Segment
 * {@code jpa}, weil das Modul {@code plaintext-root-jpa} heisst — die Bean selbst liegt in
 * {@code .web}, also genau dort, wo eine Backing-Bean hingehoert. Eine Regel, die das meldet,
 * misst den Modulnamensraum statt der Schicht und wuerde mit drei Ausnahmen fuer korrekten Code
 * beginnen. Umgekehrt bleibt sie scharf, wo es zaehlt: {@code …schuetu.service.MqttBackingBean}
 * oder {@code …schuetu.service.einstellungen.EinstellungenBackingBean} nennen nirgends eine
 * Web-Schicht und sind damit Treffer.
 *
 * <p><b>L3 (Modul-Zyklen) gibt es hier bewusst NICHT.</b> Geplant war zusaetzlich
 * {@code slices().matching("ch.plaintext.(*)..").should().beFreeOfCycles()}. Die Messung am
 * root-Bestand vom 30.08.2026 ergab <b>31 Zyklengruppen</b> ueber praktisch alle Top-Level-Pakete;
 * Drehscheibe ist {@code ch.plaintext.boot}, das von {@code framework}, {@code modules},
 * {@code settings}, {@code mailtemplate}, {@code menuesteuerung}, {@code oidc}, {@code apitoken},
 * {@code audit}, {@code arch} und {@code bus} benutzt wird und jedes davon selbst benutzt (Beispiel:
 * {@code boot -> settings -> modules -> jpa -> boot}). Eine Regel mit rund dreissig Ausnahmen prueft
 * nichts, sie dokumentiert nur — und wer sie liest, haelt sie fuer erfuellt. Die Zyklen aufzuloesen
 * ist eine eigene Etappe (Schnittstellen nach {@code plaintext-root-interfaces} ziehen, dann
 * {@code boot} entflechten) und keine Nebenwirkung dieser Massnahme. Bis dahin steht der Befund
 * hier statt in einem gruenen Test.
 *
 * <p><b>Warum der Scan am Reactor haengt und nicht am Basispaket.</b> Anders als
 * {@link PlaintextSessionBeanSerialisierbarTest} (dort ist {@code ch.plaintext} aus den Jars
 * erwuenscht) importiert dieser Test ausschliesslich die {@code target/classes} der Module des
 * eigenen Reactors ({@link ReactorLayout}). Ein Consumer soll seinen eigenen Code beurteilen und
 * nicht ueber root-Klassen aus Jars stolpern, an denen er nichts aendern kann — eine Allowlist mit
 * Eintraegen fuer fremden Code verliert ihren Sinn. Dass root mit beiden Regeln <em>ohne</em>
 * Ausnahme gruen ist (Stand 30.08.2026), ist deshalb Voraussetzung und nicht Zufall: root fuehrt
 * weiterhin keine {@code plaintext-arch-allowlist.txt}.
 *
 * <p><b>Folge davon:</b> geprueft wird nur, was zum Zeitpunkt des Testlaufs schon kompiliert ist.
 * In root laeuft dieser Test deshalb an zwei Stellen: in {@code plaintext-root-webapp} (sieht alles,
 * was davor gebaut wird) und in {@code plaintext-admin-requirements}, das erst danach an der Reihe
 * ist und im webapp-Lauf eines sauberen Builds noch gar keine Klassen hat. Zusammen decken die
 * beiden Laeufe den Reactor ab. Ein Modul, dessen {@code target/classes} in beiden Laeufen fehlt,
 * bliebe ungeprueft — deshalb die Positivkontrolle {@link #derTestSiehtEtwas()}.
 *
 * <p><b>Ausnahmen:</b> Allowlist des Reactors ({@code plaintext-arch-allowlist.txt}, Begruendung
 * Pflicht — {@link ArchAllowlist}). Ziel ist der voll qualifizierte Klassenname; bei L1 zusaetzlich
 * die engere Form {@code <Klasse> -> <Zieltyp>}, damit eine Ausnahme nicht mehr freigibt als noetig:
 * <pre>
 * layering-jsf-in-service     ch.plaintext.x.service.Foo -> org.primefaces.model.LazyDataModel  # ...
 * layering-backingbean-paket  ch.plaintext.x.service.BarBackingBean                             # ...
 * </pre>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextLayeringTest {

    /** Regel-Kennung von L1 in der Allowlist. */
    static final String REGEL_L1 = "layering-jsf-in-service";

    /** Regel-Kennung von L2 in der Allowlist. */
    static final String REGEL_L2 = "layering-backingbean-paket";

    /** Verzeichnis je Modul, aus dem die Klassen des Reactors gelesen werden. */
    private static final String KLASSEN_SUFFIX = "target/classes";

    /** Nur eigener Code — fremde Klassen in einem {@code target/classes} (Shading) gehen uns nichts an. */
    private static final String EIGENES_BASISPAKET = "ch.plaintext.";

    /** Paketsegmente, die eine Klasse zur Dienst-Schicht machen (L1). */
    private static final Set<String> DIENST_SEGMENTE = Set.of("service", "services");

    /** Paketsegmente, in denen eine Backing-Bean nichts zu suchen hat (L2). */
    private static final Set<String> UNTERE_SCHICHT_SEGMENTE =
            Set.of("service", "services", "repository", "repositories", "jpa");

    /**
     * Paketsegmente, die die Web-Schicht benennen. Steht eines davon im Paket, ist die Backing-Bean
     * dort angemeldet, wo sie hingehoert — das entwertet einen Treffer aus
     * {@link #UNTERE_SCHICHT_SEGMENTE}, der nur vom Modulnamensraum kommt.
     */
    private static final Set<String> WEB_SEGMENTE =
            Set.of("web", "view", "views", "ui", "gui", "jsf", "bean", "beans");

    /**
     * Namensraeume, die einen Dienst an einen laufenden JSF-Request binden (L1). {@code
     * jakarta.servlet..} steht bewusst nicht dabei — siehe Klassen-Javadoc.
     */
    private static final List<String> JSF_NAMENSRAEUME = List.of("jakarta.faces.", "org.primefaces.");

    private static final String SUFFIX_BACKING_BEAN = "BackingBean";

    private static final JavaClasses KLASSEN = importiereReactorKlassen();

    /**
     * Positivkontrolle. Beide Haupttests sind „nichts gefunden"-Tests; ohne Nachweis, dass ueberhaupt
     * Klassen im Blick sind, waeren sie gruen, sobald der Import ins Leere greift (fehlendes
     * {@code target/classes}, verschobene Reactor-Wurzel). Genau dieser Fehlerfall hat den
     * Session-Bean-Test schon einmal wirkungslos gemacht.
     */
    @Test
    @DisplayName("Positivkontrolle: der Layering-Scan sieht Klassen des Reactors")
    void derTestSiehtEtwas() {
        assertFalse(KLASSEN.isEmpty(),
                () -> "Keine kompilierte ch.plaintext-Klasse unter '" + KLASSEN_SUFFIX + "' im Reactor "
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
     * Regel-Einheitstest, und er ist kein Beiwerk: beide Kriterien pruefen Paket<em>segmente</em>,
     * nicht Teilzeichenketten, und L2 haengt zusaetzlich an der Web-Ausnahme. Ohne diesen Test
     * faellt weder auf, wenn {@code service} auch {@code serviceable} trifft, noch wenn die
     * Web-Ausnahme so weit wird, dass sie echte Treffer verschluckt.
     */
    @Test
    @DisplayName("Kriterien treffen Paketsegmente und Namensraeume genau")
    void kriterienTreffenNurEchteTreffer() {
        assertTrue(hatSegment("ch.plaintext.x.service", DIENST_SEGMENTE));
        assertTrue(hatSegment("ch.plaintext.x.service.impl", DIENST_SEGMENTE));
        assertTrue(hatSegment("ch.plaintext.x.services.mail", DIENST_SEGMENTE));
        assertFalse(hatSegment("ch.plaintext.x.serviceable", DIENST_SEGMENTE));
        assertFalse(hatSegment("ch.plaintext.x.web", DIENST_SEGMENTE));

        // L2: Treffer, wo keine Web-Schicht benannt ist …
        assertTrue(liegtInUntererSchicht("ch.plaintext.schuetu.service"));
        assertTrue(liegtInUntererSchicht("ch.plaintext.schuetu.service.mqtt"));
        assertTrue(liegtInUntererSchicht("ch.plaintext.x.repository"));
        assertTrue(liegtInUntererSchicht("ch.plaintext.x.jpa.model"));
        // … und kein Treffer, wo der Modulnamensraum das Segment stellt, die Bean aber in .web liegt.
        assertFalse(liegtInUntererSchicht("ch.plaintext.jpa.web"));
        assertFalse(liegtInUntererSchicht("ch.plaintext.x.service.ui"));
        assertFalse(liegtInUntererSchicht("ch.plaintext.x.web"));
        assertFalse(liegtInUntererSchicht("ch.plaintext.x.jpaquery"));

        assertTrue(istJsfTyp("jakarta.faces.context.FacesContext"));
        assertTrue(istJsfTyp("org.primefaces.model.LazyDataModel"));
        assertFalse(istJsfTyp("jakarta.persistence.EntityManager"));
        assertFalse(istJsfTyp("org.primefacesX.Foo"));
        // Bewusst KEIN Treffer: die Servlet-API bindet keinen Dienst an einen JSF-Request.
        assertFalse(istJsfTyp("jakarta.servlet.http.HttpSession"));
    }

    /** L2-Kriterium: untere Schicht benannt, Web-Schicht nicht. */
    private static boolean liegtInUntererSchicht(String paket) {
        return hatSegment(paket, UNTERE_SCHICHT_SEGMENTE) && !hatSegment(paket, WEB_SEGMENTE);
    }

    /** Trifft {@code paket} eines der {@code segmente} als vollstaendiges Paketsegment? */
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
     * Klassen aller Module des Reactors, die schon kompiliert sind — ohne Testklassen und ohne
     * fremde Namensraeume. Fehlt {@code target/classes} ueberall, bleibt die Menge leer und
     * {@link #derTestSiehtEtwas()} schlaegt an.
     */
    private static JavaClasses importiereReactorKlassen() {
        List<Path> roots = ReactorLayout.sourceRoots(KLASSEN_SUFFIX);
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

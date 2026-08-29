/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 915: Eine session-scoped Bean, die {@code Serializable} zusagt, muss es auch sein.
 *
 * <p><b>Warum es diesen Test gibt.</b> Acht Backing-Beans trugen
 * {@code @Scope("session") implements Serializable} und hielten {@code GuildSecurity} — ein
 * {@code @Component} ohne {@code Serializable} — in einem nicht-transienten Feld. Die Klasse sagt
 * damit zu, serialisierbar zu sein, und haelt ein Feld, das es nicht ist: bei Session-Serialisierung
 * wirft das {@code NotSerializableException}.
 *
 * <p><b>Warum es heute nicht aufgefallen ist.</b> Der eingebettete Tomcat serialisiert Sessions nur
 * bei eingeschalteter Persistenz; {@code application.yml} setzt unter {@code session:} lediglich
 * {@code cookie.*}. Der Fehler ist damit latent und wird scharf, sobald jemand Session-Persistenz
 * ueber einen Neustart, Session-Replikation oder einen externen Store einschaltet — also durch eine
 * Konfigurationsaenderung, die harmlos aussieht. Genau deshalb ist ein Test noetig und nicht ein
 * Ticket „bei Gelegenheit": ohne ihn faellt der Rueckfall erst im Betrieb auf.
 *
 * <p><b>Was der Test prueft.</b> Ausschliesslich ein nicht-transientes Feld, dessen Typ eine
 * <em>Spring-Bean</em> ist ({@code @Component}, {@code @Service}, {@code @Repository},
 * {@code @Controller}) und {@code Serializable} nicht implementiert. Das Kriterium ist strukturell
 * und nicht am Namen abgelesen — ein Suffix wie {@code ...Service} ist Gewohnheit, keine
 * Zusicherung. Fuer eine Spring-Bean ist die Antwort <em>immer</em> {@code transient}: der Kontext
 * injiziert sie nach einer Deserialisierung neu, es geht kein Zustand verloren.
 *
 * <p><b>Ein Feldtyp, der SELBST session-scoped ist, ist ausgenommen.</b> Er ist kein zustandsloser
 * Dienst, sondern ein Zustandstraeger — und wird er mit
 * {@code @Scope(value = "session", proxyMode = ScopedProxyMode.TARGET_CLASS)} injiziert, haelt das
 * Feld ohnehin nur einen Proxy, dessen Serialisierbarkeit an der Zielklasse haengt
 * (aufgefallen an {@code GameSelectionHolder} in schuetu).
 *
 * <p><b>{@code final}-Felder sind ausgenommen, und das ist kein Schlupfloch.</b> Bei
 * Konstruktor-Injektion (Lombok {@code @RequiredArgsConstructor}) ist {@code transient} die
 * <em>falsche</em> Antwort: bei einer Deserialisierung laeuft kein Konstruktor, das Feld bliebe
 * dauerhaft {@code null}, und weil es {@code final} ist, kann auch niemand es nachtraeglich setzen.
 * Aus einer {@code NotSerializableException} wuerde eine {@code NullPointerException} — kein
 * Fortschritt. Die Sanierung ist dort eine Designfrage und gehoert nicht in einen mechanischen
 * Durchgang (Bestand in root: Karte 915).
 *
 * <p><b>Bekannte Luecke:</b> Ein ueber ein <em>Interface</em> typisiertes Dienst-Feld traegt keine
 * Stereotyp-Annotation — das Interface ist nicht die Bean, die Implementierung ist es. Solche Felder
 * (z. B. {@code IKontaktService}, {@code MailSender}) erfasst dieser Test nicht; ein Rueckfall
 * wuerde dort nicht auffallen. Die Luecke zu schliessen hiesse, von einem Interface auf seine
 * Implementierungen zu schauen — das traegt nur, solange alle im Classpath liegen. Ein Kriterium,
 * das in einem Teil der Faelle raet, ist schlechter als eine benannte Grenze.
 *
 * <p><b>Bewusst NICHT geprueft werden Felder, die Zustand halten</b> ({@code selected : Buchung} und
 * rund 70 weitere in guild). Sie sind ebenfalls nicht serialisierbar, aber dort ist {@code transient}
 * die <em>falsche</em> Antwort; richtig ist, den Typ serialisierbar zu machen — eine Aenderung an
 * Entitaeten mit eigener Kollateralfrage. Andernfalls waere dieser Test auf Monate rot und damit
 * wirkungslos (dieselbe Abwaegung wie in Karte 860).
 *
 * <p><b>Zustandsbericht 29.08.2026, Paket R2 — geteilt statt kopiert.</b> Der Test lag als Kopie in
 * root, guild und schuetu; jede Fassung mit eigenem Basispaket. Jetzt liegt er hier in
 * {@code plaintext-root-archtests} und laeuft im Consumer via {@code <dependenciesToScan>}:
 * <ul>
 *   <li><b>Basispaket:</b> Standard {@code ch.plaintext} — damit sieht ArchUnit im Consumer auch
 *       die root-Beans aus den Jars. Das ist gewollt: root ist mit diesem Test gruen, ein Consumer
 *       bekommt so keine fremden Verstoesse, aber eine Positivkontrolle, die nie leer laeuft. Wer
 *       enger pruefen will, setzt {@code -Dplaintext.arch.basePackage=ch.plaintext.guild}
 *       (Surefire {@code systemPropertyVariables}).</li>
 *   <li><b>Ausnahmen:</b> Allowlist des Reactors ({@code plaintext-arch-allowlist.txt}, Regel
 *       {@code session-bean}, Ziel {@code Klasse.feld}, Begruendung Pflicht — {@link ArchAllowlist}).
 *       root fuehrt keine Allowlist.</li>
 * </ul>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextSessionBeanSerialisierbarTest {

    static final String ALLOWLIST_REGEL = "session-bean";

    /** Systemeigenschaft fuer ein engeres Basispaket im Consumer; Standard {@code ch.plaintext}. */
    static final String BASE_PACKAGE_PROPERTY = "plaintext.arch.basePackage";

    private static final String BASE_PACKAGE = System.getProperty(BASE_PACKAGE_PROPERTY, "ch.plaintext");

    /**
     * <b>Nicht</b> ueber {@code DO_NOT_INCLUDE_JARS} eingegrenzt — das war in guild der erste Versuch
     * und er war falsch: die webapp bindet auch die <em>eigenen</em> Module als Jars ein, der Test sah
     * danach {@code 0} Beans und wurde gruen, weil er nichts mehr prueft. Aufgedeckt hat das
     * ausschliesslich die Positivkontrolle in {@link #derTestSiehtEtwas()}.
     */
    private static final JavaClasses KLASSEN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    /**
     * Positivkontrolle, und sie ist kein Beiwerk: der Haupttest ist ein „nichts gefunden"-Test, und
     * der ist ohne Nachweis der Sichtbarkeit wertlos. Ein zu scharfer Importfilter — etwa ein
     * falsches Paket oder ein fehlendes Kompilat — laesst ihn gruen werden, weil er nichts mehr
     * prueft. Diese Zahl deckt genau das auf; die Schwelle liegt bewusst tief, aber ueber Null,
     * denn genau die Null war der Fehlerfall.
     */
    @Test
    @DisplayName("Positivkontrolle: der Test sieht session-scoped Serializable-Beans ueberhaupt")
    void derTestSiehtEtwas() {
        long beans = KLASSEN.stream()
                .filter(k -> istSessionScoped(k) && k.isAssignableTo(Serializable.class))
                .count();
        assertTrue(beans >= 1,
                () -> "Keine session-scoped Serializable-Bean unter '" + BASE_PACKAGE + "' gefunden — der "
                        + "Importfilter greift zu scharf oder das Kompilat fehlt. Ein gruener Haupttest "
                        + "wuerde hier nichts bedeuten.");
    }

    @Test
    @DisplayName("Jedes nicht-transiente Feld einer session-scoped Serializable-Bean ist serialisierbar")
    void sessionBeansSindSerialisierbar() {
        ArchAllowlist allowlist = ArchAllowlist.fuer(ALLOWLIST_REGEL);
        List<String> verstoesse = new ArrayList<>(allowlist.fehler());

        for (JavaClass klasse : KLASSEN) {
            if (!istSessionScoped(klasse) || !klasse.isAssignableTo(Serializable.class)) {
                continue;
            }
            for (JavaField feld : klasse.getFields()) {
                if (feld.getModifiers().contains(JavaModifier.STATIC)
                        || feld.getModifiers().contains(JavaModifier.TRANSIENT)
                        || feld.getModifiers().contains(JavaModifier.FINAL)) {
                    continue;
                }
                JavaClass typ = feld.getRawType();
                if (istSpringBean(typ) && !istSessionScoped(typ) && !typ.isAssignableTo(Serializable.class)) {
                    String ziel = klasse.getSimpleName() + "." + feld.getName();
                    if (!allowlist.erlaubt(ziel)) {
                        verstoesse.add("%s : %s".formatted(ziel, typ.getSimpleName()));
                    }
                }
            }
        }

        // Die Liste steht ausdruecklich in der Fehlermeldung: sie ist die Arbeitsanweisung.
        assertTrue(verstoesse.isEmpty(),
                () -> "%d nicht-serialisierbare Felder in session-scoped Serializable-Beans.\n".formatted(verstoesse.size())
                        + "Dienst -> 'transient' davorschreiben; Zustand -> Typ serialisierbar machen.\n"
                        + "Begruendete Ausnahme: '" + ALLOWLIST_REGEL + " Klasse.feld  # <Grund>' in "
                        + ArchAllowlist.DATEINAME + ".\n  "
                        + String.join("\n  ", verstoesse.stream().sorted().toList()));
    }

    private static boolean istSessionScoped(JavaClass klasse) {
        return klasse.getAnnotations().stream()
                .filter(a -> a.getRawType().getName().endsWith("Scope"))
                .anyMatch(a -> a.getProperties().values().stream()
                        .anyMatch(v -> "session".equals(String.valueOf(v))));
    }

    /**
     * Traegt der Typ eine Spring-Stereotyp-Annotation? Das ist das strukturelle Merkmal, an dem ein
     * neu injizierbarer Dienst von einem zustandstragenden Feld zu unterscheiden ist — im Gegensatz
     * zu einem Namenssuffix, das nichts zusichert.
     */
    private static boolean istSpringBean(JavaClass typ) {
        return typ.getAnnotations().stream()
                .map(a -> a.getRawType().getName())
                .anyMatch(STEREOTYPEN::contains);
    }

    private static final Set<String> STEREOTYPEN = Set.of(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller");
}

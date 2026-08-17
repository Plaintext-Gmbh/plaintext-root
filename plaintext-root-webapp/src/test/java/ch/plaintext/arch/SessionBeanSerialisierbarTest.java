/*
 * Copyright (C) plaintext.ch, 2026.
 */
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
 * <p>{@code GuildSecurity} war nur der Anlass. Der Test deckte im guild-Sourcetree <b>25</b> solche
 * Felder in <b>11</b> Klassen auf — Sonar hatte sieben gemeldet, weil es nur den „neuen Code" der
 * laufenden Periode betrachtet.
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
 * Zusicherung.
 *
 * <p>Fuer eine Spring-Bean ist die Antwort <em>immer</em> {@code transient}: der Kontext injiziert
 * sie nach einer Deserialisierung neu, es geht kein Zustand verloren. Der Fix war im Repo bereits
 * etabliert (u.a. {@code BriefeBackingBean}, {@code BuchhaltungStatistikBackingBean}). Damit ist
 * dieser Test eine Zusage, die <em>ohne Ausnahmenliste</em> erfuellbar ist — der Grund, warum er
 * genau so zugeschnitten ist.
 *
 * <p><b>{@code final}-Felder sind ausgenommen, und das ist kein Schlupfloch.</b> Bei
 * Konstruktor-Injektion (Lombok {@code @RequiredArgsConstructor}) ist {@code transient} die
 * <em>falsche</em> Antwort: bei einer Deserialisierung laeuft kein Konstruktor, das Feld bliebe
 * dauerhaft {@code null}, und weil es {@code final} ist, kann auch niemand es nachtraeglich setzen.
 * Aus einer {@code NotSerializableException} wuerde eine {@code NullPointerException} — kein Fortschritt.
 * Die Sanierung ist dort eine Designfrage (Injektionsmuster wechseln oder den Dienst serialisierbar
 * machen) und gehoert nicht in einen mechanischen Durchgang.
 *
 * <p>Betroffen in root: {@code I18nBackingBean.i18nService}, {@code RollenzuteilungBackingBean.service},
 * {@code SessionsBackingBean.sessionService}, {@code SettingsBackingBean.service} — vier Stueck,
 * gemessen. Sie bleiben ein offener Befund in Karte 915.
 *
 * <p><b>Bekannte Luecke, gemessen und nicht geschaetzt: vier weitere Felder erfasst dieser Test nicht.</b>
 * Ein ueber ein <em>Interface</em> typisiertes Dienst-Feld traegt keine Stereotyp-Annotation — das
 * Interface ist nicht die Bean, die Implementierung ist es. Betroffen waren
 * {@code BuchhaltungBackingBean.auszahlungService : IAuszahlungService},
 * {@code MemberBackingBean.kontaktService : IKontaktService} und {@code mailSender : MailSender} in
 * zwei Beans. Alle vier sind in diesem Durchgang von Hand auf {@code transient} gesetzt, <b>ein
 * Rueckfall wuerde hier aber nicht auffallen</b>.
 *
 * <p>Die Luecke zu schliessen hiesse, von einem Interface auf seine Implementierungen zu schauen —
 * das traegt nur, solange alle im Classpath liegen ({@code MailSender} kommt aus dem
 * Spring-Framework, dort greift es nicht). Ein Kriterium, das in einem Teil der Faelle raet, ist
 * schlechter als eine benannte Grenze.
 *
 * <p><b>Bewusst NICHT geprueft werden Felder, die Zustand halten</b> — {@code selected : Buchung},
 * {@code neueAuszahlung : Auszahlung} und rund 70 weitere. Sie sind ebenfalls nicht serialisierbar,
 * aber dort ist {@code transient} die <em>falsche</em> Antwort: das Feld waere nach einer
 * Deserialisierung {@code null}, und niemand fuellt es nachtraeglich. Richtig ist, den Typ
 * serialisierbar zu machen — eine Aenderung an Entitaeten mit eigener Kollateralfrage, die nicht in
 * denselben Durchgang gehoert. Andernfalls waere dieser Test auf Monate rot und damit wirkungslos
 * (dieselbe Abwaegung wie in Karte 860, wo ein Vertragstest aus genau diesem Grund verworfen wurde).
 * Der vollstaendige Bestand steht in Karte 915.
 */
class SessionBeanSerialisierbarTest {

    /**
     * Die Paketgrenze {@code ch.plaintext.guild} ist hier tragend, nicht Feinschliff. Ueber
     * {@code ch.plaintext} importiert ArchUnit auch die Backing-Beans aus {@code plaintext-root},
     * das dieses Repo als Abhaengigkeit einbindet: von 42 gefundenen Feldern lagen 17 dort. Dieser
     * Test waere in guild nie gruen geworden, weil er Klassen einfordert, die guild nicht aendern
     * kann. Jedes Repo prueft seine eigenen Klassen; die root-Faelle gehoeren in root behoben
     * (Karte 915).
     *
     * <p><b>Nicht</b> ueber {@code DO_NOT_INCLUDE_JARS} geloest — das war der erste Versuch und er
     * war falsch: {@code webapp} bindet auch die <em>eigenen</em> guild-Module als Jars ein, der
     * Test sah danach {@code 0} Beans und wurde gruen, weil er nichts mehr prueft. Aufgedeckt hat
     * das ausschliesslich die Positivkontrolle in {@link #derTestSiehtEtwas()}.
     */
    private static final JavaClasses KLASSEN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ch.plaintext");

    /**
     * Positivkontrolle, und sie ist kein Beiwerk: der Haupttest ist ein „nichts gefunden"-Test, und
     * der ist ohne Nachweis der Sichtbarkeit wertlos. Ein zu scharfer Importfilter — etwa ein
     * falsches Paket oder ein fehlendes Kompilat — laesst ihn gruen werden, weil er nichts mehr
     * prueft. Diese Zahl deckt genau das auf.
     *
     * <p>Stand 17.08.2026: 15 session-scoped Serializable-Beans im guild-Sourcetree, statisch
     * gegengezaehlt. Die Schwelle liegt bewusst tief, damit sie beim Umbau nicht stoert — aber
     * ueber Null, denn genau die Null war der Fehlerfall.
     */
    @Test
    @DisplayName("Positivkontrolle: der Test sieht die eigenen session-scoped Beans ueberhaupt")
    void derTestSiehtEtwas() {
        long beans = KLASSEN.stream()
                .filter(k -> istSessionScoped(k) && k.isAssignableTo(Serializable.class))
                .count();
        assertTrue(beans >= 10,
                () -> "Nur " + beans + " session-scoped Serializable-Beans gefunden — der Importfilter "
                        + "greift zu scharf oder das Kompilat fehlt. Ein gruener Haupttest wuerde hier "
                        + "nichts bedeuten.");
    }

    @Test
    @DisplayName("Jedes nicht-transiente Feld einer session-scoped Serializable-Bean ist serialisierbar")
    void sessionBeansSindSerialisierbar() {
        List<String> verstoesse = new ArrayList<>();

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
                if (istSpringBean(typ) && !typ.isAssignableTo(Serializable.class)) {
                    verstoesse.add("%s.%s : %s".formatted(
                            klasse.getSimpleName(), feld.getName(), typ.getSimpleName()));
                }
            }
        }

        // Die Liste steht ausdruecklich in der Fehlermeldung: sie ist die Arbeitsanweisung.
        assertTrue(verstoesse.isEmpty(),
                () -> "%d nicht-serialisierbare Felder in session-scoped Serializable-Beans.\n".formatted(verstoesse.size())
                        + "Dienst -> 'transient' davorschreiben; Zustand -> Typ serialisierbar machen.\n  "
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

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
 * Card 915: a session-scoped bean that promises {@code Serializable} has to be serializable too.
 *
 * <p><b>Why this test exists.</b> Eight backing beans carried
 * {@code @Scope("session") implements Serializable} and held {@code GuildSecurity} — a
 * {@code @Component} without {@code Serializable} — in a non-transient field. The class thereby
 * promises to be serializable while holding a field that is not: on session serialization that
 * throws {@code NotSerializableException}.
 *
 * <p><b>Why it has not shown up so far.</b> The embedded Tomcat only serializes sessions when
 * persistence is switched on; under {@code session:} the {@code application.yml} merely sets
 * {@code cookie.*}. The defect is therefore latent and goes live as soon as somebody switches on
 * session persistence across a restart, session replication or an external store — that is, through
 * a configuration change that looks harmless. Precisely for that reason a test is needed rather than
 * a ticket "when there is time": without it the regression would only surface in production.
 *
 * <p><b>What the test checks.</b> Exclusively a field whose type is a <em>Spring bean</em>
 * ({@code @Component}, {@code @Service}, {@code @Repository}, {@code @Controller}). The criterion
 * is structural and not read off the name — a suffix such as {@code ...Service} is a habit, not a
 * promise. Such a field survives a session in exactly one shape: {@code @Autowired}, {@code
 * transient}, <em>not</em> {@code final}. Then the context injects it anew after a deserialization
 * and no state is lost. Everything else is a finding, and there are two kinds
 * ({@link #befund(JavaField, JavaClass)}): {@code final} means dead after a deserialization
 * (card 1269, see below), non-transient with a non-serializable type means
 * {@code NotSerializableException} when the session is written (card 915).
 *
 * <p><b>A field type that is SESSION-SCOPED ITSELF is exempt.</b> It is not a stateless service but
 * a state carrier — and if it is injected with
 * {@code @Scope(value = "session", proxyMode = ScopedProxyMode.TARGET_CLASS)}, the field only holds
 * a proxy anyway, whose serializability depends on the target class
 * (noticed on {@code GameSelectionHolder} in schuetu).
 *
 * <p><b>{@code final} fields were exempt, and that WAS a loophole (card 1269).</b> The old
 * reasoning ran: with constructor injection (Lombok {@code @RequiredArgsConstructor})
 * {@code transient} is the wrong answer, because on a deserialization no constructor runs, the
 * field stays {@code null} forever, and being {@code final} nobody can set it afterwards — a
 * {@code NotSerializableException} would merely turn into a {@code NullPointerException}. Every
 * step of that is true, and the conclusion drawn from it was wrong: it describes a
 * <em>defect</em>, and the test answered by looking away. Measured on 19.09.2026 (card 1274): of
 * 273 service fields in 137 session-scoped {@code Serializable} beans, <b>85 (31 %) were
 * {@code final}</b> and therefore invisible here. The guard reported zero.
 *
 * <p>A guard that misses its main case is worse than none, because it produces the confidence
 * that something was checked. {@code final} is therefore a <b>violation</b> now, and the fix
 * named in the message is the house rule: <b>field injection</b>
 * ({@code @Autowired private transient …}), so the context refills the field after a
 * deserialization — not {@code final}, and not a bare {@code transient} in front of a
 * {@code final}. In guild 20 of 22 session-scoped beans already do it that way. The material
 * criterion is unchanged: only fields whose type is a Spring bean.
 *
 * <p><b>Known gap:</b> a service field typed via an <em>interface</em> carries no stereotype
 * annotation — the interface is not the bean, the implementation is. This test does not catch such
 * fields (e.g. {@code IKontaktService}, {@code MailSender}); a regression there would go unnoticed.
 * Closing the gap would mean looking from an interface to its implementations — which only holds as
 * long as all of them are on the classpath. A criterion that guesses in some of the cases is worse
 * than a named limit.
 *
 * <p><b>Deliberately NOT checked are fields that hold state</b> ({@code selected : Buchung} and
 * roughly 70 more in guild). They are not serializable either, but there {@code transient} is the
 * <em>wrong</em> answer; the right one is to make the type serializable — a change to entities with
 * collateral questions of its own. Otherwise this test would be red for months and thereby
 * ineffective (the same trade-off as in card 860).
 *
 * <p><b>Status report 29.08.2026, package R2 — shared instead of copied.</b> The test existed as a
 * copy in root, guild and schuetu; every version with its own base package. Now it lives here in
 * {@code plaintext-root-archtests} and runs in the consumer via {@code <dependenciesToScan>}:
 * <ul>
 *   <li><b>Base package:</b> default {@code ch.plaintext} — this way ArchUnit also sees the root
 *       beans from the jars inside a consumer. That is intended: root is green with this test, so a
 *       consumer gets no foreign violations, but a positive control that never runs empty. Whoever
 *       wants to check more narrowly sets {@code -Dplaintext.arch.basePackage=ch.plaintext.guild}
 *       (Surefire {@code systemPropertyVariables}).</li>
 *   <li><b>Exceptions:</b> the reactor's allowlist ({@code plaintext-arch-allowlist.txt}, rule
 *       {@code session-bean}, target {@code Klasse.feld}, justification mandatory — {@link ArchAllowlist}).
 *       root keeps no allowlist.</li>
 * </ul>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextSessionBeanSerialisierbarTest {

    static final String ALLOWLIST_REGEL = "session-bean";

    /** System property for a narrower base package in the consumer; default {@code ch.plaintext}. */
    static final String BASE_PACKAGE_PROPERTY = "plaintext.arch.basePackage";

    private static final String BASE_PACKAGE = System.getProperty(BASE_PACKAGE_PROPERTY, "ch.plaintext");

    /**
     * <b>Not</b> narrowed via {@code DO_NOT_INCLUDE_JARS} — that was the first attempt in guild and it
     * was wrong: the webapp also pulls in its <em>own</em> modules as jars, after which the test saw
     * {@code 0} beans and turned green because it no longer checked anything. Only the positive
     * control in {@link #derTestSiehtEtwas()} uncovered that.
     */
    private static final JavaClasses KLASSEN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    /**
     * Positive control, and it is no accessory: the main test is a "nothing found" test, and without
     * proof of visibility such a test is worthless. An import filter that is too strict — a wrong
     * package, say, or a missing compilation output — lets it turn green because it no longer checks
     * anything. This number uncovers exactly that; the threshold lies deliberately low, but above
     * zero, because zero was precisely the failure case.
     */
    @Test
    @DisplayName("Positivkontrolle: der Test sieht session-scoped Serializable-Beans und ihre Dienstfelder")
    void derTestSiehtEtwas() {
        long beans = KLASSEN.stream()
                .filter(k -> istSessionScoped(k) && k.isAssignableTo(Serializable.class))
                .count();
        assertTrue(beans >= 1,
                () -> "Keine session-scoped Serializable-Bean unter '" + BASE_PACKAGE + "' gefunden — der "
                        + "Importfilter greift zu scharf oder das Kompilat fehlt. Ein gruener Haupttest "
                        + "wuerde hier nichts bedeuten.");

        // Zweite Haelfte der Kontrolle (Karte 1269): Bohnen zu sehen reicht nicht, der Test muss
        // auch die FELDER sehen, um die es geht. Waere diese Zahl null, liefe die Hauptpruefung
        // ueber eine leere Menge und meldete "alles gut" — genau die Zuversicht ohne Deckung, die
        // der final-Filter jahrelang erzeugt hat.
        long dienstfelder = KLASSEN.stream()
                .filter(k -> istSessionScoped(k) && k.isAssignableTo(Serializable.class))
                .flatMap(k -> k.getFields().stream())
                .filter(f -> !f.getModifiers().contains(JavaModifier.STATIC))
                .filter(f -> istSpringBean(f.getRawType()) && !istSessionScoped(f.getRawType()))
                .count();
        assertTrue(dienstfelder >= 1,
                () -> "Keine dienst-artigen Felder in den " + beans + " gefundenen Bohnen — die "
                        + "Hauptpruefung laeuft ueber eine leere Menge und bedeutet nichts.");
    }

    @Test
    @DisplayName("Kein Dienstfeld einer session-scoped Serializable-Bean ist nach der Deserialisierung tot")
    void sessionBeansSindSerialisierbar() {
        ArchAllowlist allowlist = ArchAllowlist.fuer(ALLOWLIST_REGEL);
        List<String> verstoesse = new ArrayList<>(allowlist.fehler());

        for (JavaClass klasse : KLASSEN) {
            if (!istSessionScoped(klasse) || !klasse.isAssignableTo(Serializable.class)) {
                continue;
            }
            for (JavaField feld : klasse.getFields()) {
                if (feld.getModifiers().contains(JavaModifier.STATIC)) {
                    continue;
                }
                JavaClass typ = feld.getRawType();
                // Das materielle Kriterium, unveraendert: nur ein Feld, dessen Typ ein
                // wiederinjizierbarer Spring-Dienst ist. Zustandsfelder sind hier weiterhin nicht
                // gemeint (Begruendung im Klassenkommentar).
                if (!istSpringBean(typ) || istSessionScoped(typ)) {
                    continue;
                }
                String grund = befund(feld, typ);
                String ziel = klasse.getSimpleName() + "." + feld.getName();
                if (grund != null && !allowlist.erlaubt(ziel)) {
                    verstoesse.add("%s : %s — %s".formatted(ziel, typ.getSimpleName(), grund));
                }
            }
        }

        // The list is deliberately part of the error message: it is the work instruction.
        assertTrue(verstoesse.isEmpty(),
                () -> "%d tote oder nicht-serialisierbare Dienstfelder in session-scoped Serializable-Beans.\n".formatted(verstoesse.size())
                        + "final -> auf Feldinjektion umstellen (@Autowired + transient, NICHT final);\n"
                        + "nicht transient -> 'transient' davorschreiben; Zustand -> Typ serialisierbar machen.\n"
                        + "Begruendete Ausnahme: '" + ALLOWLIST_REGEL + " Klasse.feld  # <Grund>' in "
                        + ArchAllowlist.DATEINAME + ".\n  "
                        + String.join("\n  ", verstoesse.stream().sorted().toList()));
    }

    /**
     * What is wrong with this service field, or {@code null} if nothing is.
     *
     * <p><b>{@code final} is the case this test used to skip (card 1269), and it is the one that
     * actually bites.</b> On a deserialization <em>no constructor runs</em>. A field set only
     * through the constructor therefore stays {@code null} — and because it is {@code final},
     * nothing can set it afterwards, not even the Spring context. A
     * {@code NotSerializableException} at least shouts; this is silent and permanent, and it
     * surfaces as a {@code NullPointerException} on the first use after a session was restored
     * (cards 915/1246). Skipping it because "fixing it is a design question" turned the guard
     * into a source of confidence with nothing behind it: 31&nbsp;% of all service fields in
     * session beans were invisible to it.</p>
     *
     * <p>The way out is <b>field injection</b> ({@code @Autowired private transient …}), not
     * {@code final}: then the context refills the field after a deserialization. That is the
     * house rule — in guild 20 of 22 session-scoped beans already follow it.</p>
     *
     * <p>A {@code final} field whose type is itself {@code Serializable} and which is not
     * {@code transient} is fine: its value travels with the session, so nothing has to be
     * re-injected.</p>
     */
    private static String befund(JavaField feld, JavaClass typ) {
        boolean istFinal = feld.getModifiers().contains(JavaModifier.FINAL);
        boolean istTransient = feld.getModifiers().contains(JavaModifier.TRANSIENT);
        boolean serialisierbar = typ.isAssignableTo(Serializable.class);
        if (istFinal && istTransient) {
            return "final transient: nach der Deserialisierung dauerhaft null (kein Konstruktor, "
                    + "und final laesst sich nicht nachsetzen)";
        }
        if (istFinal && !serialisierbar) {
            return "final und nicht serialisierbar: die Session laesst sich nicht schreiben, und "
                    + "transient davorzuschreiben wuerde das Feld dauerhaft null machen";
        }
        if (!istFinal && !istTransient && !serialisierbar) {
            return "nicht transient und nicht serialisierbar: NotSerializableException beim "
                    + "Schreiben der Session";
        }
        return null;
    }

    private static boolean istSessionScoped(JavaClass klasse) {
        return klasse.getAnnotations().stream()
                .filter(a -> a.getRawType().getName().endsWith("Scope"))
                .anyMatch(a -> a.getProperties().values().stream()
                        .anyMatch(v -> "session".equals(String.valueOf(v))));
    }

    /**
     * Does the type carry a Spring stereotype annotation? That is the structural characteristic by
     * which a re-injectable service can be told apart from a state-carrying field — as opposed to a
     * name suffix, which promises nothing.
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

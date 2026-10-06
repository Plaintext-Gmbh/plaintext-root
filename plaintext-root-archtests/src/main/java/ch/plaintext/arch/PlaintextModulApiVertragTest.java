/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import ch.plaintext.modules.ModulApi;
import ch.plaintext.modules.ModulApiUmsetzung;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Shared rules for module APIs (card 1422, Daniel 04.10.2026). They run in every repository through
 * Surefire {@code dependenciesToScan}, like the other rules of this module.
 *
 * <ol>
 *   <li>A {@code @ModulApi(art = DTO)} interface is named {@code I…} ({@code IZeiteintrag}); a record
 *       DTO keeps its name.</li>
 *   <li>Every concrete class implementing a {@code @ModulApi(art = SCHNITTSTELLE)} interface carries
 *       {@code @ModulApiUmsetzung}: the contract says what, the implementation must say how — side
 *       effects included. Anonymous and local classes cannot carry it and are exempt; DTO
 *       implementations (entities) need none.</li>
 *   <li>No secrets in the texts of {@code @ModulApiUmsetzung}: they are served over MCP. Rejected are
 *       words like password/token/secret followed by a value, private addresses and internal host
 *       names.</li>
 * </ol>
 *
 * <p>The catalog processor already rejects rule 1 at compile time; the rule here also catches modules
 * that dropped the processor from their {@code annotationProcessorPaths}.
 */
@AnalyzeClasses(packages = "ch.plaintext", importOptions = ImportOption.DoNotIncludeTests.class)
public class PlaintextModulApiVertragTest {

    static final Pattern I_NAME = Pattern.compile("I[A-Z].*");

    /** Word that announces a secret, followed by a separator and a value. */
    static final Pattern GEHEIMNIS = Pattern.compile(
            "(?i)\\b(passwor[dt]|password|pw|token|secret|geheimnis|api[-_ ]?key|bearer)\\s*[:=]\\s*\\S+");
    /** Private IPv4 ranges and internal host names. */
    static final Pattern INTERN = Pattern.compile(
            "\\b(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})\\b"
                    + "|\\b[\\w-]+\\.(local|lan|internal|twin)\\b");

    @ArchTest
    static final ArchRule dtoHeisstMitI = classes()
            .that().areAnnotatedWith(ModulApi.class)
            .should(dtoMitIPraefix())
            .allowEmptyShould(true)
            .because("Karte 1422: ein zwischen Modulen übergebenes Model beginnt mit I (IZeiteintrag), "
                    + "damit es in Code und Katalog als Vertrag erkennbar ist.");

    @ArchTest
    static final ArchRule umsetzungIstBeschrieben = classes()
            .that().areNotInterfaces()
            .and().doNotHaveModifier(JavaModifier.ABSTRACT)
            .and().areNotAnonymousClasses()
            .and().areNotLocalClasses()
            .should(umsetzungBeschriebenWennModulApiSchnittstelle())
            .allowEmptyShould(true)
            .because("Karte 1422: jede Umsetzung einer @ModulApi-Schnittstelle sagt mit @ModulApiUmsetzung, "
                    + "wie sie sich verhält (Beschreibung, Seiteneffekte, Hinweise, Beispiele) — MCP und "
                    + "Modulansicht zeigen es neben dem Vertrag.");

    @ArchTest
    static final ArchRule keineGeheimnisseInBeschreibungen = classes()
            .should(keineGeheimnisseInUmsetzung())
            .allowEmptyShould(true)
            .because("Karte 1422: Beschreibungen von Umsetzungen gehen über MCP hinaus — keine Zugangsdaten, "
                    + "keine internen Adressen.");

    static ArchCondition<JavaClass> dtoMitIPraefix() {
        return new ArchCondition<>("als DTO mit I beginnen") {
            @Override
            public void check(JavaClass k, ConditionEvents events) {
                if ("DTO".equals(art(k).orElse("")) && k.isInterface() && !I_NAME.matcher(k.getSimpleName()).matches()) {
                    events.add(SimpleConditionEvent.violated(k, k.getName()
                            + " ist @ModulApi(art = DTO) und muss mit I beginnen (I" + k.getSimpleName() + ")"));
                }
            }
        };
    }

    static ArchCondition<JavaClass> umsetzungBeschriebenWennModulApiSchnittstelle() {
        return new ArchCondition<>("@ModulApiUmsetzung tragen, wenn sie eine @ModulApi-Schnittstelle umsetzen") {
            @Override
            public void check(JavaClass k, ConditionEvents events) {
                if (k.isAnnotatedWith(ModulApiUmsetzung.class)) {
                    return;
                }
                for (JavaClass i : k.getAllRawInterfaces()) {
                    if ("SCHNITTSTELLE".equals(art(i).orElse(""))) {
                        events.add(SimpleConditionEvent.violated(k, k.getName() + " setzt " + i.getName()
                                + " um, trägt aber kein @ModulApiUmsetzung"));
                        return;
                    }
                }
            }
        };
    }

    static ArchCondition<JavaClass> keineGeheimnisseInUmsetzung() {
        return new ArchCondition<>("in @ModulApiUmsetzung keine Geheimnisse und internen Adressen nennen") {
            @Override
            public void check(JavaClass k, ConditionEvents events) {
                List<JavaAnnotation<?>> annotationen = new ArrayList<>();
                k.tryGetAnnotationOfType(ModulApiUmsetzung.class.getName()).ifPresent(annotationen::add);
                k.getMethods().forEach(m -> m.tryGetAnnotationOfType(ModulApiUmsetzung.class.getName()).ifPresent(annotationen::add));
                for (JavaAnnotation<?> a : annotationen) {
                    for (String text : texte(a)) {
                        String befund = befund(text);
                        if (befund != null) {
                            events.add(SimpleConditionEvent.violated(k, k.getName() + ": @ModulApiUmsetzung enthält "
                                    + befund + " — «" + text + "»"));
                        }
                    }
                }
            }
        };
    }

    /** @return eine kurze Beschreibung des Fundes oder {@code null} */
    static String befund(String text) {
        if (GEHEIMNIS.matcher(text).find()) {
            return "einen Wert nach Passwort/Token/Secret";
        }
        if (INTERN.matcher(text.toLowerCase(Locale.ROOT)).find()) {
            return "eine interne Adresse";
        }
        return null;
    }

    private static List<String> texte(JavaAnnotation<?> a) {
        List<String> raus = new ArrayList<>();
        for (String element : List.of("beschreibung", "hinweise", "beispiele")) {
            a.get(element).ifPresent(v -> {
                if (v instanceof String s) {
                    raus.add(s);
                } else if (v instanceof Object[] liste) {
                    for (Object o : liste) {
                        raus.add(String.valueOf(o));
                    }
                }
            });
        }
        return raus;
    }

    /** Art aus {@code @ModulApi}, falls vorhanden. */
    static Optional<String> art(JavaClass k) {
        return k.tryGetAnnotationOfType(ModulApi.class.getName())
                .flatMap(a -> a.get("art"))
                .map(v -> v instanceof JavaEnumConstant e ? e.name() : String.valueOf(v));
    }
}

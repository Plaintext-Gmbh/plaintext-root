/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1423: root nutzt Jackson 3 ({@code tools.jackson.*}). Jackson 2 ist nur noch transitiv am
 * Klassenpfad (Fremdbibliotheken) und darf in eigenem Code nicht wieder auftauchen.
 *
 * <p>Verboten sind {@code com.fasterxml.jackson.databind..} und {@code com.fasterxml.jackson.core..}.
 * Erlaubt bleiben die Annotationen {@code com.fasterxml.jackson.annotation..}: Jackson 3 hat sie
 * bewusst im alten Paket gelassen, sie gelten fuer beide Linien.</p>
 *
 * <p>Geprueft wird der Bytecode der root-Module auf dem Klassenpfad von plaintext-root-webapp (Hauptcode, ohne Tests). Damit der Test nicht
 * gruen ist, weil er nichts sieht, gibt es zwei Kontrollen: eine Untergrenze fuer die Zahl der
 * gelesenen Klassen und eine absichtlich falsche Attrappe, an der die Regel rot werden muss.</p>
 */
class Jackson3VertragTest {

    /** Gemessen am 06.10.2026: 694 Klassen unter ch.plaintext auf dem Klassenpfad von root-webapp. */
    private static final int MINDESTENS_KLASSEN = 500;

    static final ArchRule KEIN_JACKSON_2 = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.fasterxml.jackson.databind..", "com.fasterxml.jackson.core..")
            .because("root nutzt Jackson 3 (tools.jackson.*), Karte 1423. Annotationen aus "
                    + "com.fasterxml.jackson.annotation bleiben erlaubt.");

    @Test
    @DisplayName("Karte 1423: kein Jackson 2 (databind/core) im root-Code")
    void keinJackson2ImRootCode() {
        JavaClasses root = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ch.plaintext");
        assertTrue(root.size() >= MINDESTENS_KLASSEN,
                "Nur " + root.size() + " root-Klassen gelesen — der Test sucht am falschen Ort.");

        KEIN_JACKSON_2.check(root);
    }

    /** Positivkontrolle: dieselbe Regel wird an einer Klasse mit Jackson 2 rot. */
    @Test
    @DisplayName("Positivkontrolle: eine Klasse mit Jackson-2-ObjectMapper verletzt die Regel")
    void regelSchlaegtBeiJackson2An() {
        EvaluationResult ergebnis = KEIN_JACKSON_2.evaluate(
                new ClassFileImporter().importClasses(Jackson2Attrappe.class));

        assertTrue(ergebnis.hasViolation(), "Die Regel erkennt Jackson 2 nicht — sie prueft nichts.");
        assertTrue(ergebnis.getFailureReport().toString().contains("com.fasterxml.jackson.databind.ObjectMapper"),
                ergebnis.getFailureReport().toString());
    }

    /** Gegenprobe: Annotationen aus dem alten Paket sind erlaubt, Jackson 3 sowieso. */
    @Test
    @DisplayName("Gegenprobe: Jackson-Annotationen und Jackson 3 sind erlaubt")
    void annotationenUndJackson3SindErlaubt() {
        EvaluationResult ergebnis = KEIN_JACKSON_2.evaluate(
                new ClassFileImporter().importClasses(ErlaubteAttrappe.class));

        assertFalse(ergebnis.hasViolation(), ergebnis.getFailureReport().toString());
    }

    /** Absichtlich falsch: nutzt den Jackson-2-Mapper. Nur fuer die Positivkontrolle. */
    @SuppressWarnings("unused")
    static final class Jackson2Attrappe {
        private final com.fasterxml.jackson.databind.ObjectMapper alt =
                new com.fasterxml.jackson.databind.ObjectMapper();
    }

    /** Erlaubt: Annotation aus dem alten Paket plus Jackson-3-Mapper. */
    @SuppressWarnings("unused")
    static final class ErlaubteAttrappe {
        @JsonProperty("name")
        private String name;
        private final tools.jackson.databind.ObjectMapper neu = tools.jackson.databind.json.JsonMapper.shared();
    }
}

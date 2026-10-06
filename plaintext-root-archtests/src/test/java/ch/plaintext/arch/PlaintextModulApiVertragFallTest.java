/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import ch.plaintext.arch.modulapi.Faelle;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1422: die Regeln aus {@link PlaintextModulApiVertragTest} gegen bekannte Fälle — in root
 * selbst gibt es noch keine {@code @ModulApi}; ohne diesen Test wären die Regeln dort leer grün.
 */
class PlaintextModulApiVertragFallTest {

    private static void rot(org.junit.jupiter.api.function.Executable e, String meldung) {
        AssertionError f = assertThrows(AssertionError.class, e);
        assertTrue(f.getMessage().contains(meldung), "erwartet «" + meldung + "» in: " + f.getMessage());
    }

    private static JavaClasses importiere(Class<?>... k) {
        return new ClassFileImporter().importClasses(k);
    }

    @Test
    @DisplayName("DTO ohne I-Präfix rot, mit I-Präfix grün")
    void dto() {
        rot(() -> PlaintextModulApiVertragTest.dtoHeisstMitI.check(importiere(Faelle.Zeiteintrag.class)), "muss mit I beginnen");
        assertDoesNotThrow(() -> PlaintextModulApiVertragTest.dtoHeisstMitI.check(importiere(Faelle.IZeiteintrag.class)));
        assertDoesNotThrow(() -> PlaintextModulApiVertragTest.dtoHeisstMitI.check(importiere(Faelle.Ortsangabe.class)),
                "Record-DTO behaelt seinen Namen");
    }

    @Test
    @DisplayName("Umsetzung ohne @ModulApiUmsetzung rot; mit, oder als DTO-Umsetzung, grün")
    void umsetzung() {
        rot(() -> PlaintextModulApiVertragTest.umsetzungIstBeschrieben
                .check(importiere(Faelle.IFotoQuelle.class, Faelle.OhneBeschreibung.class)), "trägt aber kein @ModulApiUmsetzung");
        assertDoesNotThrow(() -> PlaintextModulApiVertragTest.umsetzungIstBeschrieben
                .check(importiere(Faelle.IFotoQuelle.class, Faelle.MitBeschreibung.class, Faelle.IZeiteintrag.class,
                        Faelle.Zeit.class)));
        assertDoesNotThrow(() -> PlaintextModulApiVertragTest.umsetzungIstBeschrieben
                .check(importiere(Faelle.Kachel.class, Faelle.MeineKachel.class)), "Erweiterungspunkt braucht keine Beschreibung");
    }

    @Test
    @DisplayName("Geheimnis oder interne Adresse in der Beschreibung rot, harmloser Text grün")
    void geheimnisse() {
        rot(() -> PlaintextModulApiVertragTest.keineGeheimnisseInBeschreibungen.check(importiere(Faelle.MitToken.class)), "Passwort/Token/Secret");
        rot(() -> PlaintextModulApiVertragTest.keineGeheimnisseInBeschreibungen.check(importiere(Faelle.MitAdresse.class)), "interne Adresse");
        assertDoesNotThrow(() -> PlaintextModulApiVertragTest.keineGeheimnisseInBeschreibungen.check(importiere(Faelle.MitBeschreibung.class)));
    }
}

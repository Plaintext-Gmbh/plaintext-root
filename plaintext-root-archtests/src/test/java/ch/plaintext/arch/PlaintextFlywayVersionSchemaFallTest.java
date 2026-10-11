/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 1495: the Flyway version schema accepts epoch numbers after 15.01.2027 (V18…) and still
 * rejects everything that is not exactly one 10-digit epoch number in the plausible range.
 */
class PlaintextFlywayVersionSchemaFallTest {

    private static boolean gilt(String dateiname) {
        return PlaintextFlywayVersionSchemaTest.NEUES_SCHEMA.matcher(dateiname).matches();
    }

    @Test
    @DisplayName("V17 (heute) und V18 (ab 15.01.2027 08:00 UTC) sind gueltig")
    void epochNummernGueltig() {
        assertTrue(gilt("V1760000000__heute.sql"));
        assertTrue(gilt("V1800000000__erste_nummer_2027.sql"), "1'800'000'000 = 15.01.2027 08:00 UTC");
        assertTrue(gilt("V1899999999__ende_v18.sql"));
    }

    @Test
    @DisplayName("Alte 9-stellige Nummer, 11 Ziffern und unplausible Bereiche bleiben verboten")
    void schemaNichtAufgeweicht() {
        assertFalse(gilt("V829770587__sekunden_seit_2000.sql"), "altes Schema, 9 Ziffern");
        assertFalse(gilt("V18000000000__elf_ziffern.sql"), "11 Ziffern");
        assertFalse(gilt("V180000000__neun_ziffern.sql"), "9 Ziffern mit 18");
        assertFalse(gilt("V1600000000__vor_2023.sql"), "vor dem Epoch-Schema");
        assertFalse(gilt("V2000000000__nach_2033.sql"), "ausserhalb des plausiblen Bereichs");
        assertFalse(gilt("V1800000000_ein_unterstrich.sql"));
        assertFalse(gilt("V1800000000__ohne_endung"));
    }
}

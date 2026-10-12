/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.telefon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests für {@link Telefonnummern} (Karte 593).
 *
 * <p>Der wichtigste Fall steht ganz oben: die <b>national</b> geschriebene Nummer. Genau sie war
 * der gemessene Defekt — 53 von 236 erfassten Nummern begannen mit einer 0 und bildeten damit eine
 * WhatsApp-JID, die es nicht gibt.
 */
class TelefonnummernTest {

    /** Dieselbe Schweizer Handynummer in allen Schreibweisen, die im Bestand vorkommen. */
    @ParameterizedTest
    @ValueSource(strings = {
            "+41 79 291 40 31",
            "+41792914031",
            "0041 79 291 40 31",
            "0041792914031",
            "079 291 40 31",      // national — der Fall, an dem die alte Logik scheiterte
            "0792914031",
            "41792914031",
            "(079) 291-40-31"
    })
    void alleSchreibweisenErgebenDieselbeZiffernfolge(String schreibweise) {
        assertEquals("41792914031", Telefonnummern.normalisiere(schreibweise),
                "Schreibweise '" + schreibweise + "' muss auf die internationale Form normalisieren");
    }

    @Test
    void nationalUndInternationalSindGleich() {
        assertTrue(Telefonnummern.gleich("079 291 40 31", "+41 79 291 40 31"));
        assertTrue(Telefonnummern.gleich("+41 79 291 40 31", "0792914031"));
    }

    @Test
    void verschiedeneNummernSindNichtGleich() {
        assertFalse(Telefonnummern.gleich("079 291 40 31", "079 291 40 32"));
    }

    @Test
    void leeresIstNieGleich() {
        // Sonst passt ein Kontakt ohne Nummer auf jede Suche.
        assertFalse(Telefonnummern.gleich("", ""));
        assertFalse(Telefonnummern.gleich(null, null));
        assertFalse(Telefonnummern.gleich("", "+41 79 291 40 31"));
        assertEquals("", Telefonnummern.normalisiere(null));
        assertEquals("", Telefonnummern.normalisiere("keine Nummer"));
    }

    @Test
    void andereLandesvorwahlWirdRespektiert() {
        assertEquals("4915112345678", Telefonnummern.normalisiere("0151 12345678", "49"));
    }

    @Test
    void ohneLandesvorwahlWirdNichtGeraten() {
        // Leere Vorwahl: die führende 0 bleibt stehen, statt dass eine falsche Länderzugehörigkeit
        // erfunden wird.
        assertEquals("0792914031", Telefonnummern.normalisiere("079 291 40 31", ""));
        assertEquals("0792914031", Telefonnummern.normalisiere("079 291 40 31", null));
    }

    @Test
    void suffixIstDerMarkierteFallback() {
        assertEquals("792914031", Telefonnummern.suffix("+41 79 291 40 31"));
        assertEquals("792914031", Telefonnummern.suffix("079 291 40 31"));
        assertTrue(Telefonnummern.gleichesEnde("+49 79 291 40 31", "+41 79 291 40 31"),
                "Genau das ist das dokumentierte Falschtreffer-Risiko des Fallbacks");
    }

    @Test
    void zuKurzeNummernHabenKeinSuffix() {
        // Ohne diese Grenze würde '12345' auf jede Nummer passen, die so endet.
        assertEquals("", Telefonnummern.suffix("12345"));
        assertFalse(Telefonnummern.gleichesEnde("12345", "4179291403 12345"));
    }
}

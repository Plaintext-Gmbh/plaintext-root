/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1548: drei Farbvarianten, die gestrichenen sieben fallen auf die naechstliegende zurueck.
 */
class ThemeColorProviderVarianteTest {

    private final ThemeColorProvider provider = new ThemeColorProvider();

    @Test
    void dreiVariantenInDerReihenfolgeDesFarbwaehlers() {
        assertEquals(List.of("green", "blue", "orange"), ThemeColorProvider.VARIANTEN);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "turquoise, blue", "purple, blue",
            "avocado, green", "lime, green",
            "red, orange", "crimson, orange", "yellow, orange",
            "green, green", "blue, blue", "orange, orange", "custom, custom"})
    void gestricheneFallenAufDieNaechstliegende(String alt, String neu) {
        assertEquals(neu, ThemeColorProvider.variante(alt));
        if (!"custom".equals(neu)) {
            // Dieselbe Palette, nicht nur derselbe Name: eine alte Sitzung bekommt die Ersatzfarbe.
            assertSame(provider.getPalette(neu), provider.getPalette(alt));
        }
    }

    @Test
    void jedeGestricheneHatEinZielUnterDenDreien() {
        ThemeColorProvider.RUECKFALL.forEach((alt, neu) ->
                assertTrue(ThemeColorProvider.VARIANTEN.contains(neu), alt + " faellt auf " + neu + " — keine der drei"));
        assertEquals(7, ThemeColorProvider.RUECKFALL.size(), "zehn vorher, drei bleiben");
    }

    @Test
    void unbekanntUndNullBleibenWieSie() {
        assertNull(ThemeColorProvider.variante(null));
        assertEquals("gibtsnicht", ThemeColorProvider.variante("gibtsnicht"));
        assertSame(provider.getPalette("blue"), provider.getPalette("gibtsnicht"),
                "Unbekanntes faellt wie bisher auf Blau zurueck");
    }
}

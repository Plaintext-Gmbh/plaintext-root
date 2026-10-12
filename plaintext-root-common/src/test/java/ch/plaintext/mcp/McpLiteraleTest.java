/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Karte 469: Die Zeichenkette {@code "null"} ist kein Wert.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class McpLiteraleTest {

    @Test
    @DisplayName("Die Literale null/undefined gelten als 'nicht gesetzt'")
    void literaleWerdenAbgefangen() {
        assertNull(McpLiterale.ohneNullLiteral("null"));
        assertNull(McpLiterale.ohneNullLiteral("NULL"));
        assertNull(McpLiterale.ohneNullLiteral("undefined"));
        assertNull(McpLiterale.ohneNullLiteral(null));
    }

    @Test
    @DisplayName("Leerraum rettet das Literal nicht")
    void auchMitLeerraumBleibtEsDasLiteral() {
        // Schaerfer als das Muster aus Karte 467, das nur exakt "null" abfing: Wer " null" schickt,
        // meint dasselbe wie der, der "null" schickt.
        assertNull(McpLiterale.ohneNullLiteral(" null"));
        assertNull(McpLiterale.ohneNullLiteral("null "));
        assertNull(McpLiterale.ohneNullLiteral("\tundefined\n"));
    }

    @Test
    @DisplayName("Echte Werte bleiben unangetastet — auch solche, die 'null' enthalten")
    void echteWerteBleiben() {
        assertEquals("Nullmatt", McpLiterale.ohneNullLiteral("Nullmatt"),
                "Ein Ort darf 'Null' im Namen tragen");
        assertEquals("null null", McpLiterale.ohneNullLiteral("null null"),
                "Nur der exakte Wert zaehlt, nicht das Vorkommen");
        assertEquals("Beleg null-7", McpLiterale.ohneNullLiteral("Beleg null-7"));
    }

    @Test
    @DisplayName("Leer bleibt leer — das ist an manchen Stellen eine eigene Aussage")
    void leerstringWirdNichtUmgedeutet() {
        // update_konto.gruppe raeumt bei "" bewusst die Gruppe. Wuerde diese Methode "" zu null
        // umdeuten, kippte sie diese Absicht in ihr Gegenteil ("unveraendert lassen").
        assertEquals("", McpLiterale.ohneNullLiteral(""));
        assertEquals("   ", McpLiterale.ohneNullLiteral("   "));
    }

    @Test
    @DisplayName("Die Leerstring-Variante fuer Felder, die ohnehin auf \"\" zurueckfallen")
    void variantMitLeerstring() {
        assertEquals("", McpLiterale.ohneNullLiteralOderLeer("null"));
        assertEquals("", McpLiterale.ohneNullLiteralOderLeer(null));
        assertEquals("Biel", McpLiterale.ohneNullLiteralOderLeer("Biel"));
    }
}

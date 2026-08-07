/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 596: Was gilt als zustellbare Adresse?
 *
 * @author worker01
 * @since 07.08.2026
 */
class PlaintextEmailAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "daniel@plaintext.ch",
            "vorname.nachname@plaintext.ch",
            "a+b@sub.domain.example.com",
            "mad%test@plaintext.ch"
    })
    @DisplayName("Adressen in Mailform sind zustellbar")
    void mailformIstZustellbar(String wert) {
        assertTrue(PlaintextEmailAddress.isDeliverable(wert), wert);
        assertEquals(Optional.of(wert), PlaintextEmailAddress.asDeliverable(wert));
    }

    /**
     * Der eigentliche Zweck dieser Klasse: Genau diese Werte stehen laut Karte 596/588 in
     * {@code createdBy} bzw. im Altbestand der Benutzertabelle. Wer sie ungeprüft als Empfänger
     * nimmt, verschickt ins Nichts — und merkt es nicht.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "plafferma",          // Altbestand: reines Kürzel
            "anonymousUser",      // maschineller Schreiber ohne Audit-Principal
            "SYSTEM",
            "",
            "   ",
            "kein@punkt",         // Domain ohne Punkt
            "@plaintext.ch",      // ohne lokalen Teil
            "zwei@@plaintext.ch"
    })
    @DisplayName("Kürzel, Leerwerte und kaputte Adressen sind NICHT zustellbar")
    void nichtZustellbar(String wert) {
        assertFalse(PlaintextEmailAddress.isDeliverable(wert), String.valueOf(wert));
        assertEquals(Optional.empty(), PlaintextEmailAddress.asDeliverable(wert));
    }

    @Test
    @DisplayName("Umgebende Leerzeichen werden entfernt, nicht abgelehnt")
    void trimmtStattAbzulehnen() {
        assertEquals(Optional.of("daniel@plaintext.ch"),
                PlaintextEmailAddress.asDeliverable("  daniel@plaintext.ch  "));
    }
}

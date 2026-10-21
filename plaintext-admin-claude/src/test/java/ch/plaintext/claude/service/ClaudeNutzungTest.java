/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaudeNutzungTest {

    private static final LocalDate HEUTE = LocalDate.of(2026, 10, 21);

    private static ClaudeNutzung.Zeile z(int tageZurueck, String quelle, String modell, long aufrufe, long fehler, long in) {
        return new ClaudeNutzung.Zeile(HEUTE.minusDays(tageZurueck), quelle, modell, aufrufe, fehler, in, 10, 0, 100 * aufrufe);
    }

    private final List<ClaudeNutzung.Zeile> zeilen = List.of(
            z(0, "app", "sonnet", 4, 1, 100),
            z(6, "app", "opus", 1, 0, 1000),
            z(7, "guild", "sonnet", 2, 0, 50),
            z(29, "guild", "sonnet", 3, 3, 0),
            z(30, "alt", "sonnet", 9, 0, 9));

    @Test
    void zeitraeumeZaehlenHeute7Und30TageEinschliesslichHeute() {
        List<ClaudeNutzung.Summe> s = ClaudeNutzung.zeitraeume(zeilen, HEUTE);
        assertEquals(List.of("Heute", "7 Tage", "30 Tage"), s.stream().map(ClaudeNutzung.Summe::name).toList());
        assertEquals(4, s.get(0).aufrufe());
        assertEquals(5, s.get(1).aufrufe());
        assertEquals(10, s.get(2).aufrufe());
        assertEquals(40.0, s.get(2).fehlerquote(), 1e-9);
        assertEquals(100, s.get(2).mittlereLatenz());
        assertEquals(25.0, s.get(0).fehlerquote(), 1e-9);
    }

    @Test
    void jeModulUndModellNachTokensAbsteigend() {
        List<ClaudeNutzung.Summe> modul = ClaudeNutzung.je(zeilen, HEUTE, ClaudeNutzung.Zeile::quelle);
        assertEquals(List.of("app", "guild"), modul.stream().map(ClaudeNutzung.Summe::name).toList());
        assertEquals(1100, modul.get(0).inputTokens());
        List<ClaudeNutzung.Summe> modell = ClaudeNutzung.je(zeilen, HEUTE, ClaudeNutzung.Zeile::modell);
        assertEquals(List.of("opus", "sonnet"), modell.stream().map(ClaudeNutzung.Summe::name).toList());
    }

    @Test
    void ohneAufrufeKeineDivisionDurchNull() {
        ClaudeNutzung.Summe leer = ClaudeNutzung.zeitraeume(List.of(), HEUTE).get(0);
        assertEquals(0.0, leer.fehlerquote());
        assertEquals(0, leer.mittlereLatenz());
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.auswertung;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static ch.plaintext.auswertung.Auswertungszeitraum.fuer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boundaries are what this class is for, so they are what the tests check: the first and
 * the last instant of every period, the turn of the year, and a leap day.
 *
 * <p>"Today" is passed in rather than read from the clock. A test that computes the expected
 * value the same way the code does proves nothing; these dates are written out.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class AuswertungszeitraumTest {

    /** A Wednesday in a 53-week year; the Monday of that week is the 14th. */
    private static final LocalDate MITTWOCH = LocalDate.of(2026, 9, 16);

    @Test
    @DisplayName("HEUTE deckt genau einen Tag ab")
    void heute() {
        Auswertungszeitraum z = fuer("HEUTE", 2026, MITTWOCH).orElseThrow();

        assertEquals(LocalDate.of(2026, 9, 16), z.von());
        assertEquals(LocalDate.of(2026, 9, 17), z.bis());
        assertEquals(1, z.tage());
    }

    @Test
    @DisplayName("Die Woche laeuft Montag bis Sonntag — ISO, nicht ortsabhaengig")
    void aktuelleWoche() {
        Auswertungszeitraum z = fuer("AKTUELLE_WOCHE", 2026, MITTWOCH).orElseThrow();

        assertEquals(LocalDate.of(2026, 9, 14), z.von());
        assertEquals(LocalDate.of(2026, 9, 21), z.bis());
        assertEquals(7, z.tage());
        assertTrue(z.enthaelt(LocalDateTime.of(2026, 9, 20, 23, 59, 59, 999_000_000)),
                "Der Sonntag gehoert noch dazu, bis zur letzten Millisekunde");
        assertFalse(z.enthaelt(LocalDateTime.of(2026, 9, 21, 0, 0)),
                "Der Montag darauf nicht mehr");
    }

    @Test
    @DisplayName("Ist heute selbst Montag, beginnt die Woche heute — nicht vor sieben Tagen")
    void wocheAmMontag() {
        Auswertungszeitraum z = fuer("AKTUELLE_WOCHE", 2026, LocalDate.of(2026, 9, 14)).orElseThrow();

        assertEquals(LocalDate.of(2026, 9, 14), z.von());
    }

    @Test
    @DisplayName("VOR_n_WOCHEN zaehlt ganze Wochen zurueck")
    void vorNWochen() {
        assertEquals(LocalDate.of(2026, 9, 7), fuer("VOR_1_WOCHEN", 2026, MITTWOCH).orElseThrow().von());
        assertEquals(LocalDate.of(2026, 8, 17), fuer("VOR_4_WOCHEN", 2026, MITTWOCH).orElseThrow().von());
        assertEquals(LocalDate.of(2026, 9, 14), fuer("VOR_0_WOCHEN", 2026, MITTWOCH).orElseThrow().von());
    }

    @Test
    @DisplayName("LETZTE_WOCHE ist dasselbe wie VOR_1_WOCHEN")
    void letzteWoche() {
        assertEquals(fuer("VOR_1_WOCHEN", 2026, MITTWOCH), fuer("LETZTE_WOCHE", 2026, MITTWOCH));
    }

    @Test
    @DisplayName("Der Monatswechsel trifft den Ersten, nicht den heutigen Tag des Vormonats")
    void letzterMonat() {
        // 31.03. minus ein Monat waere der 28.02. — gefragt ist der ganze Februar.
        Auswertungszeitraum z = fuer("LETZTER_MONAT", 2026, LocalDate.of(2026, 3, 31)).orElseThrow();

        assertEquals(LocalDate.of(2026, 2, 1), z.von());
        assertEquals(LocalDate.of(2026, 3, 1), z.bis());
        assertEquals(28, z.tage());
    }

    @Test
    @DisplayName("Ein Schaltjahr-Februar hat 29 Tage")
    void schaltjahr() {
        assertEquals(29, fuer("MONAT_2", 2028, MITTWOCH).orElseThrow().tage());
        assertEquals(28, fuer("MONAT_2", 2026, MITTWOCH).orElseThrow().tage());
    }

    @Test
    @DisplayName("Quartale decken das Jahr lueckenlos und ueberschneidungsfrei ab")
    void quartale() {
        assertEquals(LocalDate.of(2026, 1, 1), fuer("Q1", 2026, MITTWOCH).orElseThrow().von());
        assertEquals(LocalDate.of(2026, 4, 1), fuer("Q1", 2026, MITTWOCH).orElseThrow().bis());
        assertEquals(LocalDate.of(2026, 4, 1), fuer("Q2", 2026, MITTWOCH).orElseThrow().von());
        assertEquals(LocalDate.of(2026, 10, 1), fuer("Q4", 2026, MITTWOCH).orElseThrow().von());
        assertEquals(LocalDate.of(2027, 1, 1), fuer("Q4", 2026, MITTWOCH).orElseThrow().bis());

        long summe = 0;
        for (int q = 1; q <= 4; q++) {
            summe += fuer("Q" + q, 2026, MITTWOCH).orElseThrow().tage();
        }
        assertEquals(365, summe, "Die vier Quartale muessen zusammen das ganze Jahr ergeben");
    }

    @Test
    @DisplayName("Die beiden Halbjahre ergeben zusammen das Jahr")
    void halbjahre() {
        long h1 = fuer("HALBJAHR_1", 2026, MITTWOCH).orElseThrow().tage();
        long h2 = fuer("HALBJAHR_2", 2026, MITTWOCH).orElseThrow().tage();

        assertEquals(181, h1);
        assertEquals(184, h2);
        assertEquals(fuer("GANZES_JAHR", 2026, MITTWOCH).orElseThrow().tage(), h1 + h2);
    }

    @Test
    @DisplayName("Das ganze Jahr endet am 1. Januar des Folgejahres — Silvester gehoert dazu")
    void ganzesJahr() {
        Auswertungszeitraum z = fuer("GANZES_JAHR", 2026, MITTWOCH).orElseThrow();

        assertEquals(LocalDate.of(2027, 1, 1), z.bis());
        assertTrue(z.enthaelt(LocalDateTime.of(2026, 12, 31, 23, 59)));
        assertFalse(z.enthaelt(LocalDateTime.of(2027, 1, 1, 0, 0)));
    }

    @Test
    @DisplayName("ALLE_JAHRE spannt ueber alles, egal welcher Schluessel")
    void alleJahre() {
        Auswertungszeitraum z = fuer("HEUTE", Auswertungszeitraum.ALLE_JAHRE, MITTWOCH).orElseThrow();

        assertTrue(z.enthaelt(LocalDateTime.of(1999, 1, 1, 0, 0)));
        assertTrue(z.enthaelt(LocalDateTime.of(2090, 1, 1, 0, 0)));
    }

    @Test
    @DisplayName("Ein unbekannter Schluessel liefert nichts — statt still auf einen Vorgabewert zu fallen")
    void unbekannt() {
        assertTrue(fuer("GIBTSNICHT", 2026, MITTWOCH).isEmpty());
        assertTrue(fuer(null, 2026, MITTWOCH).isEmpty());
        assertTrue(fuer("  ", 2026, MITTWOCH).isEmpty());
        assertTrue(fuer("MONAT_13", 2026, MITTWOCH).isEmpty());
        assertTrue(fuer("MONAT_0", 2026, MITTWOCH).isEmpty());
        assertTrue(fuer("Q5", 2026, MITTWOCH).isEmpty());
        assertTrue(fuer("MONAT_x", 2026, MITTWOCH).isEmpty());
        assertTrue(fuer("VOR_x_WOCHEN", 2026, MITTWOCH).isEmpty());
    }

    @Test
    @DisplayName("Eine verdrehte Spanne wird beim Bauen abgewiesen, nicht spaeter beim Rechnen")
    void verdrehteSpanne() {
        assertThrows(IllegalArgumentException.class,
                () -> new Auswertungszeitraum(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Auswertungszeitraum(null, LocalDate.of(2026, 4, 1)));
    }

    @Test
    @DisplayName("Ein null-Zeitpunkt liegt in keinem Zeitraum")
    void nullZeitpunkt() {
        assertFalse(fuer("GANZES_JAHR", 2026, MITTWOCH).orElseThrow().enthaelt(null));
    }

    @Test
    @DisplayName("Die Zeitpunkt-Grenzen passen zu den Datumsgrenzen")
    void zeitpunktGrenzen() {
        Auswertungszeitraum z = fuer("MONAT_9", 2026, MITTWOCH).orElseThrow();

        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), z.vonZeitpunkt());
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), z.bisZeitpunkt());
    }
}

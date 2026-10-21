/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.linktoken;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1559: was der Vertrag selbst entscheidet - alte Zeilen ohne Recht sind Lese-Links (wie im Inventar), und der
 * letzte gültige Tag gilt noch.
 */
class LinkTokenTest {

    @Test
    void rechtAusGespeichertemWert() {
        assertEquals(LinkRecht.LESEN, LinkRecht.von(null));
        assertEquals(LinkRecht.LESEN, LinkRecht.von(""));
        assertEquals(LinkRecht.LESEN, LinkRecht.von("lesen"));
        assertEquals(LinkRecht.LESEN, LinkRecht.von("unbekannt"));
        assertEquals(LinkRecht.SCHREIBEN, LinkRecht.von(" Schreiben "));
        assertEquals(LinkRecht.SCHREIBEN, LinkRecht.von("rw"));
        assertEquals("schreiben", LinkRecht.SCHREIBEN.wert());
    }

    @Test
    void ablaufUndSchreibrecht() {
        LocalDate heute = LocalDate.of(2026, 10, 21);
        LinkToken bisHeute = token(LinkRecht.LESEN, heute);
        assertFalse(bisHeute.abgelaufen(heute), "der letzte Tag gilt noch");
        assertTrue(bisHeute.abgelaufen(heute.plusDays(1)));
        assertFalse(token(LinkRecht.LESEN, null).abgelaufen(heute.plusYears(10)), "ohne Datum unbefristet");
        assertFalse(bisHeute.schreiben());
        assertTrue(token(LinkRecht.SCHREIBEN, null).schreiben());
        LinkTokenAntrag a = new LinkTokenAntrag("files", "anna/a.txt", null, "anna", null, heute);
        assertEquals("files", a.art());
    }

    private static LinkToken token(LinkRecht recht, LocalDate bis) {
        return new LinkToken("1", null, "drawio", "plan.drawio", recht, null, null, "", null, bis, 0, null);
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit-Tests fuer die wiederverwendbare {@link SearchHitDTO}: All-Args-/No-Args-Konstruktor,
 * Getter/Setter, sowie die Lombok-generierten {@code equals}/{@code hashCode}/{@code toString}.
 */
class SearchHitDTOTest {

    @Test
    void allArgsKonstruktorSetztAlleFelder() {
        SearchHitDTO dto = new SearchHitDTO("Mahnung", "2026-01-01", "korrespondenz.html?id=1", "pi pi-envelope", 42);
        assertEquals("Mahnung", dto.getTitle());
        assertEquals("2026-01-01", dto.getSubtitle());
        assertEquals("korrespondenz.html?id=1", dto.getLink());
        assertEquals("pi pi-envelope", dto.getIcon());
        assertEquals(42, dto.getScore());
    }

    @Test
    void noArgsKonstruktorUndSetter() {
        SearchHitDTO dto = new SearchHitDTO();
        assertNull(dto.getTitle());
        dto.setTitle("Kontakt");
        dto.setSubtitle("Mandant: m1");
        dto.setLink("kontakte.html");
        dto.setIcon("pi pi-user");
        dto.setScore(7);

        assertEquals("Kontakt", dto.getTitle());
        assertEquals("Mandant: m1", dto.getSubtitle());
        assertEquals("kontakte.html", dto.getLink());
        assertEquals("pi pi-user", dto.getIcon());
        assertEquals(7, dto.getScore());
    }

    @Test
    void equalsUndHashCode() {
        SearchHitDTO a = new SearchHitDTO("T", "s", "l.html", "pi", 5);
        SearchHitDTO b = new SearchHitDTO("T", "s", "l.html", "pi", 5);
        SearchHitDTO c = new SearchHitDTO("T", "s", "l.html", "pi", 6);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(null, a);
        assertNotEquals("keinDTO", a);
    }

    @Test
    void toStringEnthaeltFeldwerte() {
        SearchHitDTO dto = new SearchHitDTO("Titel", "Sub", "link.html", "pi pi-star", 9);
        String s = dto.toString();
        assertTrue(s.contains("Titel"));
        assertTrue(s.contains("link.html"));
        assertTrue(s.contains("9"));
    }
}

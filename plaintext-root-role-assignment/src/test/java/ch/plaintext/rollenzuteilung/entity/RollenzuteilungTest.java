/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class RollenzuteilungTest {

    @Test
    void isCurrentlyValid_activeAndNoDateBounds_returnsTrue() {
        Rollenzuteilung rz = new Rollenzuteilung();
        rz.setActive(true);
        rz.setValidFrom(null);
        rz.setValidUntil(null);

        assertTrue(rz.isCurrentlyValid());
    }

    @Test
    void isCurrentlyValid_inactive_returnsFalse() {
        Rollenzuteilung rz = new Rollenzuteilung();
        rz.setActive(false);
        rz.setValidFrom(null);
        rz.setValidUntil(null);

        assertFalse(rz.isCurrentlyValid());
    }

    @Test
    void isCurrentlyValid_activeAndValidFromInPast_returnsTrue() {
        Rollenzuteilung rz = new Rollenzuteilung();
        rz.setActive(true);
        rz.setValidFrom(LocalDateTime.now().minusDays(1));
        rz.setValidUntil(null);

        assertTrue(rz.isCurrentlyValid());
    }

    @Test
    void isCurrentlyValid_activeAndValidFromInFuture_returnsFalse() {
        Rollenzuteilung rz = new Rollenzuteilung();
        rz.setActive(true);
        rz.setValidFrom(LocalDateTime.now().plusDays(1));
        rz.setValidUntil(null);

        assertFalse(rz.isCurrentlyValid());
    }

    @Test
    void isCurrentlyValid_activeAndValidUntilInFuture_returnsTrue() {
        Rollenzuteilung rz = new Rollenzuteilung();
        rz.setActive(true);
        rz.setValidFrom(null);
        rz.setValidUntil(LocalDateTime.now().plusDays(1));

        assertTrue(rz.isCurrentlyValid());
    }

    @Test
    void isCurrentlyValid_activeAndValidUntilInPast_returnsFalse() {
        Rollenzuteilung rz = new Rollenzuteilung();
        rz.setActive(true);
        rz.setValidFrom(null);
        rz.setValidUntil(LocalDateTime.now().minusDays(1));

        assertFalse(rz.isCurrentlyValid());
    }

    @Test
    void isCurrentlyValid_activeAndWithinDateRange_returnsTrue() {
        Rollenzuteilung rz = new Rollenzuteilung();
        rz.setActive(true);
        rz.setValidFrom(LocalDateTime.now().minusDays(1));
        rz.setValidUntil(LocalDateTime.now().plusDays(1));

        assertTrue(rz.isCurrentlyValid());
    }

    @Test
    void isCurrentlyValid_activeButBeforeDateRange_returnsFalse() {
        Rollenzuteilung rz = new Rollenzuteilung();
        rz.setActive(true);
        rz.setValidFrom(LocalDateTime.now().plusDays(1));
        rz.setValidUntil(LocalDateTime.now().plusDays(30));

        assertFalse(rz.isCurrentlyValid());
    }

    @Test
    void isCurrentlyValid_activeButAfterDateRange_returnsFalse() {
        Rollenzuteilung rz = new Rollenzuteilung();
        rz.setActive(true);
        rz.setValidFrom(LocalDateTime.now().minusDays(30));
        rz.setValidUntil(LocalDateTime.now().minusDays(1));

        assertFalse(rz.isCurrentlyValid());
    }

    @Test
    void defaultActive_isTrue() {
        Rollenzuteilung rz = new Rollenzuteilung();
        assertTrue(rz.getActive());
    }

    @Test
    void settersAndGetters() {
        Rollenzuteilung rz = new Rollenzuteilung();
        LocalDateTime now = LocalDateTime.now();

        rz.setId(1L);
        rz.setUsername("admin");
        rz.setMandat("test-mandat");
        rz.setRoleName("ROLE_ADMIN");
        rz.setActive(true);
        rz.setValidFrom(now.minusDays(1));
        rz.setValidUntil(now.plusDays(30));
        rz.setBeschreibung("Admin role");
        rz.setCreatedDate(now);
        rz.setCreatedBy("system");
        rz.setLastModifiedDate(now);
        rz.setLastModifiedBy("system");

        assertEquals(1L, rz.getId());
        assertEquals("admin", rz.getUsername());
        assertEquals("test-mandat", rz.getMandat());
        assertEquals("ROLE_ADMIN", rz.getRoleName());
        assertTrue(rz.getActive());
        assertNotNull(rz.getValidFrom());
        assertNotNull(rz.getValidUntil());
        assertEquals("Admin role", rz.getBeschreibung());
        assertEquals(now, rz.getCreatedDate());
        assertEquals("system", rz.getCreatedBy());
        assertEquals(now, rz.getLastModifiedDate());
        assertEquals("system", rz.getLastModifiedBy());
    }

    @Test
    void allArgsConstructor() {
        LocalDateTime now = LocalDateTime.now();
        Rollenzuteilung rz = new Rollenzuteilung(
                1L, "user", "mandat", "ROLE_USER",
                true, now.minusDays(1), now.plusDays(30),
                "Desc", now, "creator", now, "modifier"
        );

        assertEquals(1L, rz.getId());
        assertEquals("user", rz.getUsername());
        assertEquals("mandat", rz.getMandat());
        assertEquals("ROLE_USER", rz.getRoleName());
        assertEquals("Desc", rz.getBeschreibung());
    }

    @Test
    void equalsAndHashCode() {
        Rollenzuteilung rz1 = new Rollenzuteilung();
        rz1.setId(1L);
        rz1.setUsername("user");
        rz1.setMandat("m");
        rz1.setRoleName("ROLE_USER");

        Rollenzuteilung rz2 = new Rollenzuteilung();
        rz2.setId(1L);
        rz2.setUsername("user");
        rz2.setMandat("m");
        rz2.setRoleName("ROLE_USER");

        assertEquals(rz1, rz2);
        assertEquals(rz1.hashCode(), rz2.hashCode());
    }
}

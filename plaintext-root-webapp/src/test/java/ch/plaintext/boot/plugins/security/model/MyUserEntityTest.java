/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MyUserEntity - the user entity model.
 */
class MyUserEntityTest {

    @Test
    void shouldHaveDefaultValues() {
        MyUserEntity user = new MyUserEntity();

        assertNull(user.getId());
        assertNull(user.getUsername());
        assertEquals("", user.getPassword());
        assertEquals("", user.getStartpage());
        assertEquals("", user.getAutologinKey());
        assertNotNull(user.getRoles());
        assertTrue(user.getRoles().isEmpty());
    }

    @Test
    void addRole_shouldAddRole() {
        MyUserEntity user = new MyUserEntity();
        user.addRole("admin");

        assertTrue(user.getRoles().contains("admin"));
        assertEquals(1, user.getRoles().size());
    }

    @Test
    void addRole_shouldNotDuplicateRole() {
        MyUserEntity user = new MyUserEntity();
        user.addRole("admin");
        user.addRole("admin");

        assertEquals(1, user.getRoles().size());
    }

    @Test
    void removeRole_shouldRemoveRole() {
        MyUserEntity user = new MyUserEntity();
        user.addRole("admin");
        user.addRole("user");

        user.removeRole("admin");

        assertFalse(user.getRoles().contains("admin"));
        assertTrue(user.getRoles().contains("user"));
        assertEquals(1, user.getRoles().size());
    }

    @Test
    void removeRole_shouldDoNothing_whenRoleNotPresent() {
        MyUserEntity user = new MyUserEntity();
        user.addRole("admin");

        user.removeRole("nonexistent");

        assertEquals(1, user.getRoles().size());
    }

    @Test
    void getMandat_shouldReturnMandat_fromRoles() {
        MyUserEntity user = new MyUserEntity();
        user.getRoles().add("PROPERTY_MANDAT_production");

        assertEquals("production", user.getMandat());
    }

    @Test
    void getMandat_shouldReturnNull_whenNoMandatRole() {
        MyUserEntity user = new MyUserEntity();
        user.addRole("admin");
        user.addRole("user");

        assertNull(user.getMandat());
    }

    @Test
    void getMandat_shouldReturnNull_whenRolesNull() {
        MyUserEntity user = new MyUserEntity();
        user.setRoles(null);

        assertNull(user.getMandat());
    }

    @Test
    void getMandat_shouldBeCaseInsensitive() {
        MyUserEntity user = new MyUserEntity();
        user.getRoles().add("property_mandat_dev");

        assertEquals("dev", user.getMandat());
    }

    @Test
    void setMandat_shouldReplaceMandatRole() {
        MyUserEntity user = new MyUserEntity();
        user.addRole("admin");
        user.setMandat("production");

        assertEquals("production", user.getMandat());
        assertTrue(user.getRoles().contains("admin"));
        assertTrue(user.getRoles().contains("PROPERTY_MANDAT_PRODUCTION"));
    }

    @Test
    void setMandat_shouldRemoveExistingMandat() {
        MyUserEntity user = new MyUserEntity();
        user.setMandat("old");
        user.setMandat("new");

        assertEquals("new", user.getMandat());
        // Should only have one mandat role
        long mandatCount = user.getRoles().stream()
                .filter(r -> r.toUpperCase().startsWith("PROPERTY_MANDAT_"))
                .count();
        assertEquals(1, mandatCount);
    }

    @Test
    void setMandat_shouldHandleNull() {
        MyUserEntity user = new MyUserEntity();
        user.setMandat("existing");
        user.setMandat(null);

        assertNull(user.getMandat());
    }

    @Test
    void setMandat_shouldHandleEmpty() {
        MyUserEntity user = new MyUserEntity();
        user.setMandat("existing");
        user.setMandat("");

        assertNull(user.getMandat());
    }

    @Test
    void setMandat_shouldHandleWhitespace() {
        MyUserEntity user = new MyUserEntity();
        user.setMandat("existing");
        user.setMandat("  ");

        assertNull(user.getMandat());
    }

    @Test
    void setMandat_shouldCreateRolesSet_whenNull() {
        MyUserEntity user = new MyUserEntity();
        user.setRoles(null);

        user.setMandat("test");

        assertNotNull(user.getRoles());
        assertEquals("test", user.getMandat());
    }

    @Test
    void shouldSupportAllFields() {
        MyUserEntity user = new MyUserEntity();
        user.setId(42L);
        user.setUsername("test@example.com");
        user.setPassword("hashed");
        user.setStartpage("dashboard.html");
        user.setAutologinKey("abc123");

        assertEquals(42L, user.getId());
        assertEquals("test@example.com", user.getUsername());
        assertEquals("hashed", user.getPassword());
        assertEquals("dashboard.html", user.getStartpage());
        assertEquals("abc123", user.getAutologinKey());
    }

    @Test
    void setRoles_shouldReplaceRoles() {
        MyUserEntity user = new MyUserEntity();
        user.addRole("admin");

        Set<String> newRoles = new HashSet<>();
        newRoles.add("user");
        newRoles.add("root");
        user.setRoles(newRoles);

        assertFalse(user.getRoles().contains("admin"));
        assertTrue(user.getRoles().contains("user"));
        assertTrue(user.getRoles().contains("root"));
    }
}

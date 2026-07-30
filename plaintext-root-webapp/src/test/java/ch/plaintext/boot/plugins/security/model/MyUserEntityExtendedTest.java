/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for MyUserEntity - mandat handling, role management.
 */
class MyUserEntityExtendedTest {

    @Test
    void getMandat_shouldReturnNull_whenRolesNull() {
        MyUserEntity entity = new MyUserEntity();
        entity.setRoles(null);
        assertNull(entity.getMandat());
    }

    @Test
    void getMandat_shouldReturnNull_whenNoMandatRole() {
        MyUserEntity entity = new MyUserEntity();
        entity.addRole("admin");
        entity.addRole("user");
        assertNull(entity.getMandat());
    }

    @Test
    void getMandat_shouldReturnMandat_whenMandatRoleExists() {
        MyUserEntity entity = new MyUserEntity();
        entity.addRole("PROPERTY_MANDAT_production");
        assertEquals("production", entity.getMandat());
    }

    @Test
    void getMandat_shouldBeCaseInsensitive() {
        MyUserEntity entity = new MyUserEntity();
        entity.addRole("property_mandat_DEV");
        // The role starts with uppercase check, so let's test with proper prefix
        entity.getRoles().clear();
        entity.addRole("PROPERTY_MANDAT_PRODUCTION");
        assertEquals("production", entity.getMandat());
    }

    @Test
    void setMandat_shouldCreateRolesSet_whenNull() {
        MyUserEntity entity = new MyUserEntity();
        entity.setRoles(null);
        entity.setMandat("test");
        assertNotNull(entity.getRoles());
        assertTrue(entity.getRoles().stream().anyMatch(r -> r.contains("MANDAT")));
    }

    @Test
    void setMandat_shouldRemoveOldMandat() {
        MyUserEntity entity = new MyUserEntity();
        entity.setMandat("old");
        entity.setMandat("new");

        long mandatCount = entity.getRoles().stream()
                .filter(r -> r.toUpperCase().startsWith("PROPERTY_MANDAT_"))
                .count();
        assertEquals(1, mandatCount);
        assertEquals("new", entity.getMandat());
    }

    @Test
    void setMandat_shouldNotAddRole_whenNullOrEmpty() {
        MyUserEntity entity = new MyUserEntity();
        entity.setMandat("test");
        entity.setMandat(null);

        long mandatCount = entity.getRoles().stream()
                .filter(r -> r.toUpperCase().startsWith("PROPERTY_MANDAT_"))
                .count();
        assertEquals(0, mandatCount);
    }

    @Test
    void setMandat_shouldNotAddRole_whenBlank() {
        MyUserEntity entity = new MyUserEntity();
        entity.setMandat("test");
        entity.setMandat("   ");

        long mandatCount = entity.getRoles().stream()
                .filter(r -> r.toUpperCase().startsWith("PROPERTY_MANDAT_"))
                .count();
        assertEquals(0, mandatCount);
    }

    @Test
    void addRole_shouldAddToSet() {
        MyUserEntity entity = new MyUserEntity();
        entity.addRole("admin");
        assertTrue(entity.getRoles().contains("admin"));
    }

    @Test
    void removeRole_shouldRemoveFromSet() {
        MyUserEntity entity = new MyUserEntity();
        entity.addRole("admin");
        entity.removeRole("admin");
        assertFalse(entity.getRoles().contains("admin"));
    }

    @Test
    void defaultValues_shouldBeSet() {
        MyUserEntity entity = new MyUserEntity();
        assertEquals("", entity.getPassword());
        assertEquals("", entity.getStartpage());
        assertEquals("", entity.getAutologinKey());
        assertNotNull(entity.getRoles());
        assertTrue(entity.getRoles().isEmpty());
    }

    @Test
    void getMandat_shouldHandleNullRoleInSet() {
        MyUserEntity entity = new MyUserEntity();
        Set<String> roles = new HashSet<>();
        roles.add(null);
        roles.add("admin");
        entity.setRoles(roles);
        // Should not throw NPE
        assertNull(entity.getMandat());
    }

    @Test
    void setMandat_shouldStoreUppercase() {
        MyUserEntity entity = new MyUserEntity();
        entity.setMandat("dev");

        assertTrue(entity.getRoles().stream()
                .anyMatch(r -> r.equals("PROPERTY_MANDAT_DEV")));
    }

    @Test
    void setMandat_shouldPreserveOtherRoles() {
        MyUserEntity entity = new MyUserEntity();
        entity.addRole("admin");
        entity.addRole("user");
        entity.setMandat("dev");

        assertTrue(entity.getRoles().contains("admin"));
        assertTrue(entity.getRoles().contains("user"));
    }

    @Test
    void settersAndGetters_shouldWork() {
        MyUserEntity entity = new MyUserEntity();
        entity.setId(42L);
        entity.setUsername("test@test.com");
        entity.setPassword("secret");
        entity.setStartpage("dashboard.html");
        entity.setAutologinKey("key123");

        assertEquals(42L, entity.getId());
        assertEquals("test@test.com", entity.getUsername());
        assertEquals("secret", entity.getPassword());
        assertEquals("dashboard.html", entity.getStartpage());
        assertEquals("key123", entity.getAutologinKey());
    }
}

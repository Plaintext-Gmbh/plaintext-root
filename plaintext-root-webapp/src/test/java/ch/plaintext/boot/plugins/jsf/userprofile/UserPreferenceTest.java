/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UserPreference - the user preference data model.
 */
class UserPreferenceTest {

    @Test
    void shouldHaveDefaultValues() {
        UserPreference pref = new UserPreference();

        assertEquals("layout-sidebar", pref.getMenuMode());
        assertEquals("light", pref.getDarkMode());
        assertEquals("green", pref.getComponentTheme());
        assertEquals("light", pref.getTopbarTheme());
        assertEquals("light", pref.getMenuTheme());
        assertEquals("outlined", pref.getInputStyle());
        assertFalse(pref.isLightLogo());
        assertTrue(pref.isMenuStatic());
        assertNull(pref.getCustomColor());
        assertEquals("", pref.getUser());
    }

    @Test
    void getUniqueId_shouldReturnUser() {
        UserPreference pref = new UserPreference();
        pref.setUser("testuser@example.com");

        assertEquals("testuser@example.com", pref.getUniqueId());
    }

    @Test
    void setUniqueId_shouldSetUser() {
        UserPreference pref = new UserPreference();
        pref.setUniqueId("admin@example.com");

        assertEquals("admin@example.com", pref.getUser());
        assertEquals("admin@example.com", pref.getUniqueId());
    }

    @Test
    void shouldAllowSettingAllFields() {
        UserPreference pref = new UserPreference();

        pref.setMenuMode("layout-horizontal");
        pref.setDarkMode("dark");
        pref.setComponentTheme("blue");
        pref.setTopbarTheme("dark");
        pref.setMenuTheme("dark");
        pref.setInputStyle("filled");
        pref.setLightLogo(true);
        pref.setMenuStatic(false);
        pref.setUser("user@test.com");

        assertEquals("layout-horizontal", pref.getMenuMode());
        assertEquals("dark", pref.getDarkMode());
        assertEquals("blue", pref.getComponentTheme());
        assertEquals("dark", pref.getTopbarTheme());
        assertEquals("dark", pref.getMenuTheme());
        assertEquals("filled", pref.getInputStyle());
        assertTrue(pref.isLightLogo());
        assertFalse(pref.isMenuStatic());
        assertEquals("user@test.com", pref.getUser());
    }

    @Test
    void shouldImplementSimpleStorableInterface() {
        UserPreference pref = new UserPreference();
        pref.setUniqueId("test-id");
        assertEquals("test-id", pref.getUniqueId());

        pref.setUniqueId("new-id");
        assertEquals("new-id", pref.getUniqueId());
    }

    @Test
    void equals_shouldWorkCorrectly() {
        UserPreference pref1 = new UserPreference();
        pref1.setUser("user1");
        pref1.setDarkMode("dark");

        UserPreference pref2 = new UserPreference();
        pref2.setUser("user1");
        pref2.setDarkMode("dark");

        assertEquals(pref1, pref2);
    }

    @Test
    void equals_shouldDetectDifferences() {
        UserPreference pref1 = new UserPreference();
        pref1.setUser("user1");

        UserPreference pref2 = new UserPreference();
        pref2.setUser("user2");

        assertNotEquals(pref1, pref2);
    }

    @Test
    void hashCode_shouldBeConsistent() {
        UserPreference pref = new UserPreference();
        pref.setUser("user1");

        int hash1 = pref.hashCode();
        int hash2 = pref.hashCode();

        assertEquals(hash1, hash2);
    }

    @Test
    void toString_shouldNotBeNull() {
        UserPreference pref = new UserPreference();
        assertNotNull(pref.toString());
    }
}

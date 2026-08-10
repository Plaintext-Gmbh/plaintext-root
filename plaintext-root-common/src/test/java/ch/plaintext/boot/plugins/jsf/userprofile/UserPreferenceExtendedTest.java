/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for UserPreference - all default values and getters/setters.
 */
class UserPreferenceExtendedTest {

    @Test
    void defaults_shouldBeSet() {
        UserPreference pref = new UserPreference();

        assertEquals("layout-sidebar", pref.getMenuMode());
        assertEquals("light", pref.getDarkMode());
        assertEquals("green", pref.getComponentTheme());
        assertEquals("light", pref.getTopbarTheme());
        assertEquals("light", pref.getMenuTheme());
        assertEquals("outlined", pref.getInputStyle());
        assertFalse(pref.isLightLogo());
        assertTrue(pref.isMenuStatic());
        assertEquals("", pref.getUser());
    }

    @Test
    void uniqueId_shouldMapToUser() {
        UserPreference pref = new UserPreference();

        pref.setUniqueId("test@example.com");
        assertEquals("test@example.com", pref.getUniqueId());
        assertEquals("test@example.com", pref.getUser());
    }

    @Test
    void setters_shouldWork() {
        UserPreference pref = new UserPreference();

        pref.setMenuMode("layout-horizontal");
        pref.setDarkMode("dark");
        pref.setComponentTheme("blue");
        pref.setTopbarTheme("dark");
        pref.setMenuTheme("dark");
        pref.setInputStyle("filled");
        pref.setLightLogo(true);
        pref.setMenuStatic(false);

        assertEquals("layout-horizontal", pref.getMenuMode());
        assertEquals("dark", pref.getDarkMode());
        assertEquals("blue", pref.getComponentTheme());
        assertEquals("dark", pref.getTopbarTheme());
        assertEquals("dark", pref.getMenuTheme());
        assertEquals("filled", pref.getInputStyle());
        assertTrue(pref.isLightLogo());
        assertFalse(pref.isMenuStatic());
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.objstore;

import ch.plaintext.boot.plugins.jsf.userprofile.UserPreference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SimpleStorableConverter - XStream converter for SimpleStorable objects.
 */
class SimpleStorableConverterTest {

    private SimpleStorableConverter converter;

    @BeforeEach
    void setUp() {
        converter = new SimpleStorableConverter();
    }

    @Test
    void shouldConvertToXml() {
        UserPreference pref = new UserPreference();
        pref.setUniqueId("test-user");
        pref.setDarkMode("dark");

        String xml = converter.convertToDatabaseColumn(pref);

        assertNotNull(xml);
        assertTrue(xml.contains("dark"));
        assertTrue(xml.contains("test-user"));
    }

    @Test
    void shouldConvertFromXml() {
        UserPreference original = new UserPreference();
        original.setUniqueId("test-user");
        original.setDarkMode("dark");
        original.setComponentTheme("blue");

        String xml = converter.convertToDatabaseColumn(original);
        SimpleStorable restored = converter.convertToEntityAttribute(xml);

        assertNotNull(restored);
        assertTrue(restored instanceof UserPreference);
        UserPreference restoredPref = (UserPreference) restored;
        assertEquals("test-user", restoredPref.getUniqueId());
        assertEquals("dark", restoredPref.getDarkMode());
        assertEquals("blue", restoredPref.getComponentTheme());
    }

    @Test
    void shouldHandleNullInput() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void shouldHandleInvalidXml() {
        SimpleStorable result = converter.convertToEntityAttribute("invalid xml");
        assertNull(result);
    }

    @Test
    void shouldRoundTrip() {
        UserPreference original = new UserPreference();
        original.setUniqueId("roundtrip-user");
        original.setDarkMode("light");
        original.setMenuMode("layout-horizontal");
        original.setInputStyle("filled");
        original.setMenuStatic(false);
        original.setLightLogo(true);

        String xml = converter.convertToDatabaseColumn(original);
        SimpleStorable restored = converter.convertToEntityAttribute(xml);

        assertNotNull(restored);
        UserPreference restoredPref = (UserPreference) restored;
        assertEquals("roundtrip-user", restoredPref.getUniqueId());
        assertEquals("light", restoredPref.getDarkMode());
        assertEquals("layout-horizontal", restoredPref.getMenuMode());
        assertEquals("filled", restoredPref.getInputStyle());
        assertFalse(restoredPref.isMenuStatic());
        assertTrue(restoredPref.isLightLogo());
    }
}

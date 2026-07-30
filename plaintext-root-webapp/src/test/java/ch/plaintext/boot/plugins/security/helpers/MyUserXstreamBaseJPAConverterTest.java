/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.helpers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MyUserXstreamBaseJPAConverter and MyUserSetConverter.
 */
class MyUserXstreamBaseJPAConverterTest {

    private MyUserSetConverter converter;

    @BeforeEach
    void setUp() {
        converter = new MyUserSetConverter();
    }

    @Test
    void shouldConvertSetToXml() {
        Set<String> roles = new HashSet<>();
        roles.add("admin");
        roles.add("user");

        String xml = converter.convertToDatabaseColumn(roles);

        assertNotNull(xml);
        assertTrue(xml.contains("admin"));
        assertTrue(xml.contains("user"));
    }

    @Test
    void shouldConvertXmlToSet() {
        Set<String> original = new HashSet<>();
        original.add("admin");
        original.add("user");

        String xml = converter.convertToDatabaseColumn(original);
        Set<String> restored = converter.convertToEntityAttribute(xml);

        assertNotNull(restored);
        assertEquals(2, restored.size());
        assertTrue(restored.contains("admin"));
        assertTrue(restored.contains("user"));
    }

    @Test
    void shouldHandleNullXml() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void shouldHandleEmptySet() {
        Set<String> empty = new HashSet<>();

        String xml = converter.convertToDatabaseColumn(empty);
        Set<String> restored = converter.convertToEntityAttribute(xml);

        assertNotNull(restored);
        assertTrue(restored.isEmpty());
    }

    @Test
    void shouldHandleInvalidXml() {
        Set<String> result = converter.convertToEntityAttribute("invalid xml content");

        assertNull(result);
    }

    @Test
    void shouldRoundTripWithSpecialCharacters() {
        Set<String> original = new HashSet<>();
        original.add("PROPERTY_MANDAT_DEV");
        original.add("ROLE_ADMIN");

        String xml = converter.convertToDatabaseColumn(original);
        Set<String> restored = converter.convertToEntityAttribute(xml);

        assertEquals(original, restored);
    }

    @Test
    void shouldHandleSingleElement() {
        Set<String> original = new HashSet<>();
        original.add("singleRole");

        String xml = converter.convertToDatabaseColumn(original);
        Set<String> restored = converter.convertToEntityAttribute(xml);

        assertEquals(1, restored.size());
        assertTrue(restored.contains("singleRole"));
    }
}

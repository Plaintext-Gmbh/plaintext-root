/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jpa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SetConverter - JPA converter for Set of String.
 */
class SetConverterTest {

    private SetConverter converter;

    @BeforeEach
    void setUp() {
        converter = new SetConverter();
    }

    @Test
    void convertToDatabaseColumn_shouldReturnXml() {
        Set<String> set = new HashSet<>(Set.of("admin", "user"));

        String xml = converter.convertToDatabaseColumn(set);

        assertNotNull(xml);
        assertTrue(xml.contains("admin"));
        assertTrue(xml.contains("user"));
    }

    @Test
    void convertToEntityAttribute_shouldReturnSet() {
        Set<String> original = new HashSet<>(Set.of("role1", "role2"));
        String xml = converter.convertToDatabaseColumn(original);

        Set<String> result = converter.convertToEntityAttribute(xml);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains("role1"));
        assertTrue(result.contains("role2"));
    }

    @Test
    void convertToEntityAttribute_shouldReturnNull_forNullInput() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void convertToEntityAttribute_shouldReturnNull_forInvalidXml() {
        assertNull(converter.convertToEntityAttribute("invalid"));
    }

    @Test
    void roundTrip_shouldPreserveEmptySet() {
        Set<String> empty = new HashSet<>();
        String xml = converter.convertToDatabaseColumn(empty);
        Set<String> result = converter.convertToEntityAttribute(xml);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}

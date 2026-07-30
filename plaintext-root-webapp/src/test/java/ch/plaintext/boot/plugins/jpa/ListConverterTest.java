/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jpa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ListConverter - JPA converter for List of String.
 */
class ListConverterTest {

    private ListConverter converter;

    @BeforeEach
    void setUp() {
        converter = new ListConverter();
    }

    @Test
    void convertToDatabaseColumn_shouldReturnXml() {
        List<String> list = new ArrayList<>(Arrays.asList("a", "b", "c"));

        String xml = converter.convertToDatabaseColumn(list);

        assertNotNull(xml);
        assertTrue(xml.contains("a"));
        assertTrue(xml.contains("b"));
        assertTrue(xml.contains("c"));
    }

    @Test
    void convertToEntityAttribute_shouldReturnList() {
        List<String> original = new ArrayList<>(Arrays.asList("x", "y"));
        String xml = converter.convertToDatabaseColumn(original);

        List<String> result = converter.convertToEntityAttribute(xml);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains("x"));
        assertTrue(result.contains("y"));
    }

    @Test
    void convertToEntityAttribute_shouldReturnNull_forNullInput() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void convertToEntityAttribute_shouldReturnNull_forInvalidXml() {
        assertNull(converter.convertToEntityAttribute("not valid xml"));
    }

    @Test
    void roundTrip_shouldPreserveEmptyList() {
        List<String> empty = new ArrayList<>();
        String xml = converter.convertToDatabaseColumn(empty);
        List<String> result = converter.convertToEntityAttribute(xml);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}

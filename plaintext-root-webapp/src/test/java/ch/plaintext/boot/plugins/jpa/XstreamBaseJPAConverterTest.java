/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jpa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for XstreamBaseJPAConverter and its subclasses - JPA attribute converters.
 */
class XstreamBaseJPAConverterTest {

    private ListConverter listConverter;
    private SetConverter setConverter;

    @BeforeEach
    void setUp() {
        listConverter = new ListConverter();
        setConverter = new SetConverter();
    }

    // ==================== ListConverter Tests ====================

    @Test
    void listConverter_shouldConvertListToXml() {
        List<String> list = new ArrayList<>();
        list.add("item1");
        list.add("item2");

        String xml = listConverter.convertToDatabaseColumn(list);

        assertNotNull(xml);
        assertTrue(xml.contains("item1"));
        assertTrue(xml.contains("item2"));
    }

    @Test
    void listConverter_shouldConvertXmlToList() {
        List<String> original = new ArrayList<>();
        original.add("item1");
        original.add("item2");

        String xml = listConverter.convertToDatabaseColumn(original);
        List<String> restored = listConverter.convertToEntityAttribute(xml);

        assertNotNull(restored);
        assertEquals(2, restored.size());
        assertTrue(restored.contains("item1"));
        assertTrue(restored.contains("item2"));
    }

    @Test
    void listConverter_shouldHandleNullInput() {
        assertNull(listConverter.convertToEntityAttribute(null));
    }

    @Test
    void listConverter_shouldHandleEmptyList() {
        List<String> list = new ArrayList<>();

        String xml = listConverter.convertToDatabaseColumn(list);
        List<String> restored = listConverter.convertToEntityAttribute(xml);

        assertNotNull(restored);
        assertTrue(restored.isEmpty());
    }

    @Test
    void listConverter_shouldHandleInvalidXml() {
        List<String> result = listConverter.convertToEntityAttribute("not valid xml <><>");

        assertNull(result);
    }

    // ==================== SetConverter Tests ====================

    @Test
    void setConverter_shouldConvertSetToXml() {
        Set<String> set = new HashSet<>();
        set.add("value1");
        set.add("value2");

        String xml = setConverter.convertToDatabaseColumn(set);

        assertNotNull(xml);
        assertTrue(xml.contains("value1"));
        assertTrue(xml.contains("value2"));
    }

    @Test
    void setConverter_shouldConvertXmlToSet() {
        Set<String> original = new HashSet<>();
        original.add("value1");
        original.add("value2");

        String xml = setConverter.convertToDatabaseColumn(original);
        Set<String> restored = setConverter.convertToEntityAttribute(xml);

        assertNotNull(restored);
        assertEquals(2, restored.size());
        assertTrue(restored.contains("value1"));
        assertTrue(restored.contains("value2"));
    }

    @Test
    void setConverter_shouldHandleNullInput() {
        assertNull(setConverter.convertToEntityAttribute(null));
    }

    @Test
    void setConverter_shouldHandleEmptySet() {
        Set<String> set = new HashSet<>();

        String xml = setConverter.convertToDatabaseColumn(set);
        Set<String> restored = setConverter.convertToEntityAttribute(xml);

        assertNotNull(restored);
        assertTrue(restored.isEmpty());
    }

    @Test
    void setConverter_shouldHandleInvalidXml() {
        Set<String> result = setConverter.convertToEntityAttribute("invalid xml");

        assertNull(result);
    }

    // ==================== Round-trip Tests ====================

    @Test
    void listConverter_shouldPreserveOrder() {
        List<String> original = new ArrayList<>();
        original.add("z");
        original.add("a");
        original.add("m");

        String xml = listConverter.convertToDatabaseColumn(original);
        List<String> restored = listConverter.convertToEntityAttribute(xml);

        assertEquals("z", restored.get(0));
        assertEquals("a", restored.get(1));
        assertEquals("m", restored.get(2));
    }

    @Test
    void listConverter_shouldHandleSingleElement() {
        List<String> original = new ArrayList<>();
        original.add("only");

        String xml = listConverter.convertToDatabaseColumn(original);
        List<String> restored = listConverter.convertToEntityAttribute(xml);

        assertEquals(1, restored.size());
        assertEquals("only", restored.get(0));
    }

    @Test
    void setConverter_shouldHandleSingleElement() {
        Set<String> original = new HashSet<>();
        original.add("only");

        String xml = setConverter.convertToDatabaseColumn(original);
        Set<String> restored = setConverter.convertToEntityAttribute(xml);

        assertEquals(1, restored.size());
        assertTrue(restored.contains("only"));
    }
}

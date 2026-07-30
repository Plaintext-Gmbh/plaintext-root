/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XstreamBaseJPAConverterTest {

    private XstreamBaseJPAConverter<List<String>> converter;

    @BeforeEach
    void setUp() {
        converter = new XstreamBaseJPAConverter<>();
    }

    // -------------------------------------------------------------------------
    // convertToDatabaseColumn
    // -------------------------------------------------------------------------

    @Test
    void convertToDatabaseColumn_withList_returnsXml() {
        ArrayList<String> input = new ArrayList<>();
        input.add("tag1");
        input.add("tag2");
        input.add("tag3");
        String xml = converter.convertToDatabaseColumn(input);

        assertNotNull(xml);
        assertTrue(xml.contains("tag1"));
        assertTrue(xml.contains("tag2"));
        assertTrue(xml.contains("tag3"));
    }

    @Test
    void convertToDatabaseColumn_withNull_returnsXml() {
        // XStream will serialize null
        String xml = converter.convertToDatabaseColumn(null);
        assertNotNull(xml);
    }

    @Test
    void convertToDatabaseColumn_withEmptyList_returnsXml() {
        ArrayList<String> input = new ArrayList<>();
        String xml = converter.convertToDatabaseColumn(input);
        assertNotNull(xml);
    }

    // -------------------------------------------------------------------------
    // convertToEntityAttribute
    // -------------------------------------------------------------------------

    @Test
    void convertToEntityAttribute_withNull_returnsNull() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void convertToEntityAttribute_withEmptyString_returnsNull() {
        assertNull(converter.convertToEntityAttribute(""));
    }

    @Test
    void convertToEntityAttribute_roundTrip() {
        ArrayList<String> original = new ArrayList<>();
        original.add("alpha");
        original.add("beta");
        original.add("gamma");
        String xml = converter.convertToDatabaseColumn(original);

        @SuppressWarnings("unchecked")
        List<String> restored = converter.convertToEntityAttribute(xml);

        assertNotNull(restored);
        assertEquals(3, restored.size());
        assertEquals("alpha", restored.get(0));
        assertEquals("beta", restored.get(1));
        assertEquals("gamma", restored.get(2));
    }

    @Test
    void convertToEntityAttribute_invalidXml_fallsBackToCommaSplit() {
        // When XStream fails, the converter falls back to splitting by comma
        @SuppressWarnings("unchecked")
        List<String> result = converter.convertToEntityAttribute("a,b,c");

        assertNotNull(result);
        assertEquals(3, result.size());
        assertTrue(result.contains("a"));
        assertTrue(result.contains("b"));
        assertTrue(result.contains("c"));
    }

    @Test
    void convertToEntityAttribute_singleValueFallback() {
        @SuppressWarnings("unchecked")
        List<String> result = converter.convertToEntityAttribute("singlevalue");

        assertNotNull(result);
        // Falls back to comma split; single value = list of 1
        assertEquals(1, result.size());
        assertEquals("singlevalue", result.get(0));
    }
}

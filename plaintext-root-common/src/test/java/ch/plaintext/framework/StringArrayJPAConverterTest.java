/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StringArrayJPAConverterTest {

    private StringArrayJPAConverter converter;

    @BeforeEach
    void setUp() {
        converter = new StringArrayJPAConverter();
    }

    // -------------------------------------------------------------------------
    // Inherits XstreamBaseJPAConverter behavior
    // -------------------------------------------------------------------------

    @Test
    void convertToDatabaseColumn_withStringList() {
        ArrayList<String> input = new ArrayList<>();
        input.add("one");
        input.add("two");
        input.add("three");
        String xml = converter.convertToDatabaseColumn(input);
        assertNotNull(xml);
        assertTrue(xml.contains("one"));
        assertTrue(xml.contains("two"));
        assertTrue(xml.contains("three"));
    }

    @Test
    void convertToEntityAttribute_withNull_returnsNull() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void convertToEntityAttribute_withEmpty_returnsNull() {
        assertNull(converter.convertToEntityAttribute(""));
    }

    @Test
    void roundTrip_preservesList() {
        ArrayList<String> original = new ArrayList<>();
        original.add("hello");
        original.add("world");
        String xml = converter.convertToDatabaseColumn(original);

        @SuppressWarnings("unchecked")
        List<String> restored = converter.convertToEntityAttribute(xml);

        assertNotNull(restored);
        assertEquals(2, restored.size());
        assertEquals("hello", restored.get(0));
        assertEquals("world", restored.get(1));
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SuperModelTest {

    private SuperModel model;

    @BeforeEach
    void setUp() {
        model = new SuperModel();
    }

    // -------------------------------------------------------------------------
    // Default values
    // -------------------------------------------------------------------------

    @Test
    void defaults_areSetCorrectly() {
        assertNull(model.getId());
        assertFalse(model.getDeleted());
        assertNull(model.getCreatedBy());
        assertNull(model.getCreatedDate());
        assertNull(model.getLastModifiedBy());
        assertNull(model.getLastModifiedDate());
        assertNull(model.getMandat());
        assertNotNull(model.getTags());
        assertTrue(model.getTags().isEmpty());
    }

    // -------------------------------------------------------------------------
    // getKey / setKey
    // -------------------------------------------------------------------------

    @Test
    void getKey_returnsIdAsString() {
        model.setId(42L);
        assertEquals("42", model.getKey());
    }

    @Test
    void getKey_withNullId_returnsNullString() {
        assertEquals("null", model.getKey());
    }

    @Test
    void setKey_setsIdFromString() {
        model.setKey("123");
        assertEquals(123L, model.getId());
    }

    @Test
    void setKey_invalidNumber_throwsException() {
        assertThrows(NumberFormatException.class, () -> model.setKey("abc"));
    }

    // -------------------------------------------------------------------------
    // getFields
    // -------------------------------------------------------------------------

    @Test
    void getFields_returnsFields() {
        List<Field> fields = model.getFields();
        assertNotNull(fields);
        assertFalse(fields.isEmpty());
    }

    @Test
    void getFields_includesSuperModelFields() {
        List<Field> fields = model.getFields();
        List<String> fieldNames = fields.stream()
                .map(Field::getName)
                .toList();
        // SuperModel has these declared fields
        assertTrue(fieldNames.contains("id"));
        assertTrue(fieldNames.contains("deleted"));
        assertTrue(fieldNames.contains("mandat"));
        assertTrue(fieldNames.contains("tags"));
    }

    // -------------------------------------------------------------------------
    // getFieldsOhneSuper
    // -------------------------------------------------------------------------

    @Test
    void getFieldsOhneSuper_returnsOnlyOwnPrivateFields() {
        List<Field> fields = model.getFieldsOhneSuper();
        assertNotNull(fields);
        // SuperModel's own private fields
        for (Field field : fields) {
            assertTrue(java.lang.reflect.Modifier.isPrivate(field.getModifiers()),
                    "Field " + field.getName() + " should be private");
        }
    }

    // -------------------------------------------------------------------------
    // isFiledEmty (sic - typo in source)
    // -------------------------------------------------------------------------

    @Test
    void isFiledEmty_withNullField_returnsTrue() {
        model.setId(null);
        assertTrue(model.isFiledEmty("id"));
    }

    @Test
    void isFiledEmty_withSetField_returnsFalse() {
        model.setId(42L);
        assertFalse(model.isFiledEmty("id"));
    }

    @Test
    void isFiledEmty_withEmptyTags_returnsTrue() {
        model.setTags(new ArrayList<>());
        assertTrue(model.isFiledEmty("tags"));
    }

    @Test
    void isFiledEmty_withPopulatedTags_returnsFalse() {
        List<String> tags = new ArrayList<>();
        tags.add("tag1");
        model.setTags(tags);
        assertFalse(model.isFiledEmty("tags"));
    }

    @Test
    void isFiledEmty_nonexistentField_returnsFalse() {
        // A field that doesn't exist should just not match and return false
        assertFalse(model.isFiledEmty("nonexistentField"));
    }

    @Test
    void isFiledEmty_caseInsensitive() {
        model.setMandat(null);
        assertTrue(model.isFiledEmty("MANDAT"));
        assertTrue(model.isFiledEmty("Mandat"));
    }

    // -------------------------------------------------------------------------
    // Setters and getters
    // -------------------------------------------------------------------------

    @Test
    void setAndGetId() {
        model.setId(100L);
        assertEquals(100L, model.getId());
    }

    @Test
    void setAndGetDeleted() {
        model.setDeleted(true);
        assertTrue(model.getDeleted());
    }

    @Test
    void setAndGetCreatedBy() {
        model.setCreatedBy("admin");
        assertEquals("admin", model.getCreatedBy());
    }

    @Test
    void setAndGetCreatedDate() {
        Date now = new Date();
        model.setCreatedDate(now);
        assertEquals(now, model.getCreatedDate());
    }

    @Test
    void setAndGetLastModifiedBy() {
        model.setLastModifiedBy("editor");
        assertEquals("editor", model.getLastModifiedBy());
    }

    @Test
    void setAndGetLastModifiedDate() {
        Date now = new Date();
        model.setLastModifiedDate(now);
        assertEquals(now, model.getLastModifiedDate());
    }

    @Test
    void setAndGetMandat() {
        model.setMandat("testMandat");
        assertEquals("testMandat", model.getMandat());
    }

    @Test
    void setAndGetTags() {
        List<String> tags = List.of("a", "b", "c");
        model.setTags(new ArrayList<>(tags));
        assertEquals(3, model.getTags().size());
        assertTrue(model.getTags().containsAll(tags));
    }

    // -------------------------------------------------------------------------
    // XstreamStorable default methods inherited
    // -------------------------------------------------------------------------

    @Test
    void getDruckmodus_defaultIsFalse() {
        // SuperModel implements XstreamStorable, inheriting default methods
        assertFalse(model.getDruckmodus());
    }

    @Test
    void getIndex_defaultIsDefault() {
        assertEquals("default", model.getIndex());
    }

    @Test
    void getIndex1_defaultIsDefault() {
        assertEquals("default", model.getIndex1());
    }

    @Test
    void getIndex2_defaultIsDefault() {
        assertEquals("default", model.getIndex2());
    }

    @Test
    void getIndex3_defaultIsDefault() {
        assertEquals("default", model.getIndex3());
    }

    @Test
    void getIndex4_defaultIsDefault() {
        assertEquals("default", model.getIndex4());
    }
}

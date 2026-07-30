/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class FieldMetadataTest {

    private FieldMetadata metadata;

    @BeforeEach
    void setUp() {
        metadata = new FieldMetadata();
        metadata.setFieldName("testField");
        metadata.setDisplayName("Test Field");
        metadata.setFieldType(String.class);
        metadata.setFieldTypeName("String");
    }

    @Test
    void isDisplayable_normalField_returnsTrue() {
        metadata.setTransient(false);
        metadata.setCollection(false);
        assertTrue(metadata.isDisplayable());
    }

    @Test
    void isDisplayable_transientField_returnsFalse() {
        metadata.setTransient(true);
        assertFalse(metadata.isDisplayable());
    }

    @Test
    void isDisplayable_collectionField_returnsFalse() {
        metadata.setCollection(true);
        assertFalse(metadata.isDisplayable());
    }

    @Test
    void isEditable_normalField_returnsTrue() {
        metadata.setId(false);
        metadata.setAudit(false);
        metadata.setTransient(false);
        metadata.setCollection(false);
        metadata.setReadOnly(false);
        assertTrue(metadata.isEditable());
    }

    @Test
    void isEditable_idField_returnsFalse() {
        metadata.setId(true);
        assertFalse(metadata.isEditable());
    }

    @Test
    void isEditable_auditField_returnsFalse() {
        metadata.setAudit(true);
        assertFalse(metadata.isEditable());
    }

    @Test
    void isEditable_readOnlyField_returnsFalse() {
        metadata.setReadOnly(true);
        assertFalse(metadata.isEditable());
    }

    @Test
    void isEditable_transientField_returnsFalse() {
        metadata.setTransient(true);
        assertFalse(metadata.isEditable());
    }

    @Test
    void isEditable_collectionField_returnsFalse() {
        metadata.setCollection(true);
        assertFalse(metadata.isEditable());
    }

    @Test
    void getSimpleTypeName_localDateTime_returnsDateTime() {
        metadata.setFieldType(LocalDateTime.class);
        assertEquals("DateTime", metadata.getSimpleTypeName());
    }

    @Test
    void getSimpleTypeName_localDate_returnsDate() {
        metadata.setFieldType(LocalDate.class);
        assertEquals("Date", metadata.getSimpleTypeName());
    }

    @Test
    void getSimpleTypeName_localTime_returnsTime() {
        metadata.setFieldType(LocalTime.class);
        assertEquals("Time", metadata.getSimpleTypeName());
    }

    @Test
    void getSimpleTypeName_bigDecimal_returnsDecimal() {
        metadata.setFieldType(BigDecimal.class);
        assertEquals("Decimal", metadata.getSimpleTypeName());
    }

    @Test
    void getSimpleTypeName_string_returnsString() {
        metadata.setFieldType(String.class);
        assertEquals("String", metadata.getSimpleTypeName());
    }

    @Test
    void getSimpleTypeName_nullType_fallsBackToTypeName() {
        metadata.setFieldType(null);
        metadata.setFieldTypeName("CustomType");
        assertEquals("CustomType", metadata.getSimpleTypeName());
    }

    @Test
    void getSimpleTypeName_nullTypeAndNullTypeName_returnsString() {
        metadata.setFieldType(null);
        metadata.setFieldTypeName(null);
        assertEquals("String", metadata.getSimpleTypeName());
    }

    @Test
    void isNumeric_integerClass_returnsTrue() {
        metadata.setFieldType(Integer.class);
        assertTrue(metadata.isNumeric());
    }

    @Test
    void isNumeric_intPrimitive_returnsTrue() {
        metadata.setFieldType(int.class);
        assertTrue(metadata.isNumeric());
    }

    @Test
    void isNumeric_longClass_returnsTrue() {
        metadata.setFieldType(Long.class);
        assertTrue(metadata.isNumeric());
    }

    @Test
    void isNumeric_longPrimitive_returnsTrue() {
        metadata.setFieldType(long.class);
        assertTrue(metadata.isNumeric());
    }

    @Test
    void isNumeric_floatClass_returnsTrue() {
        metadata.setFieldType(Float.class);
        assertTrue(metadata.isNumeric());
    }

    @Test
    void isNumeric_doubleClass_returnsTrue() {
        metadata.setFieldType(Double.class);
        assertTrue(metadata.isNumeric());
    }

    @Test
    void isNumeric_bigDecimal_returnsTrue() {
        metadata.setFieldType(BigDecimal.class);
        assertTrue(metadata.isNumeric());
    }

    @Test
    void isNumeric_string_returnsFalse() {
        metadata.setFieldType(String.class);
        assertFalse(metadata.isNumeric());
    }

    @Test
    void isNumeric_nullType_returnsFalse() {
        metadata.setFieldType(null);
        assertFalse(metadata.isNumeric());
    }

    @Test
    void isDateTime_utilDate_returnsTrue() {
        metadata.setFieldType(Date.class);
        assertTrue(metadata.isDateTime());
    }

    @Test
    void isDateTime_localDateTime_returnsTrue() {
        metadata.setFieldType(LocalDateTime.class);
        assertTrue(metadata.isDateTime());
    }

    @Test
    void isDateTime_localDate_returnsTrue() {
        metadata.setFieldType(LocalDate.class);
        assertTrue(metadata.isDateTime());
    }

    @Test
    void isDateTime_localTime_returnsTrue() {
        metadata.setFieldType(LocalTime.class);
        assertTrue(metadata.isDateTime());
    }

    @Test
    void isDateTime_sqlDate_returnsTrue() {
        metadata.setFieldType(java.sql.Date.class);
        assertTrue(metadata.isDateTime());
    }

    @Test
    void isDateTime_sqlTimestamp_returnsTrue() {
        metadata.setFieldType(java.sql.Timestamp.class);
        assertTrue(metadata.isDateTime());
    }

    @Test
    void isDateTime_string_returnsFalse() {
        metadata.setFieldType(String.class);
        assertFalse(metadata.isDateTime());
    }

    @Test
    void isDateTime_nullType_returnsFalse() {
        metadata.setFieldType(null);
        assertFalse(metadata.isDateTime());
    }

    @Test
    void isBooleanType_booleanClass_returnsTrue() {
        metadata.setFieldType(Boolean.class);
        assertTrue(metadata.isBooleanType());
    }

    @Test
    void isBooleanType_booleanPrimitive_returnsTrue() {
        metadata.setFieldType(boolean.class);
        assertTrue(metadata.isBooleanType());
    }

    @Test
    void isBooleanType_string_returnsFalse() {
        metadata.setFieldType(String.class);
        assertFalse(metadata.isBooleanType());
    }

    @Test
    void isBooleanType_nullType_returnsFalse() {
        metadata.setFieldType(null);
        assertFalse(metadata.isBooleanType());
    }

    @Test
    void isText_stringType_returnsTrue() {
        metadata.setFieldType(String.class);
        assertTrue(metadata.isText());
    }

    @Test
    void isText_nullType_returnsTrue() {
        metadata.setFieldType(null);
        assertTrue(metadata.isText());
    }

    @Test
    void isText_integerType_returnsFalse() {
        metadata.setFieldType(Integer.class);
        assertFalse(metadata.isText());
    }

    @Test
    void defaultMaxLength_is255() {
        FieldMetadata fresh = new FieldMetadata();
        assertEquals(255, fresh.getMaxLength());
    }
}

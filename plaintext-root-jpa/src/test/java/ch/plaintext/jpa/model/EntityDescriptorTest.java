/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntityDescriptorTest {

    private EntityDescriptor descriptor;

    @BeforeEach
    void setUp() {
        descriptor = new EntityDescriptor();
        descriptor.setEntityName("TestEntity");
        descriptor.setDisplayName("Test Entity");
        descriptor.setEntityClass(String.class);
    }

    @Test
    void getDisplayFields_filtersOutTransientAndCollectionFields() {
        FieldMetadata normal = new FieldMetadata();
        normal.setFieldName("name");
        normal.setTransient(false);
        normal.setCollection(false);

        FieldMetadata transientField = new FieldMetadata();
        transientField.setFieldName("temp");
        transientField.setTransient(true);
        transientField.setCollection(false);

        FieldMetadata collectionField = new FieldMetadata();
        collectionField.setFieldName("items");
        collectionField.setTransient(false);
        collectionField.setCollection(true);

        descriptor.setFields(List.of(normal, transientField, collectionField));

        List<FieldMetadata> displayFields = descriptor.getDisplayFields();
        assertEquals(1, displayFields.size());
        assertEquals("name", displayFields.get(0).getFieldName());
    }

    @Test
    void getDisplayFields_limitsTo10Columns() {
        for (int i = 0; i < 15; i++) {
            FieldMetadata field = new FieldMetadata();
            field.setFieldName("field" + i);
            field.setTransient(false);
            field.setCollection(false);
            descriptor.getFields().add(field);
        }

        List<FieldMetadata> displayFields = descriptor.getDisplayFields();
        assertEquals(10, displayFields.size());
    }

    @Test
    void getEditableFields_filtersCorrectly() {
        FieldMetadata editable = new FieldMetadata();
        editable.setFieldName("name");
        editable.setId(false);
        editable.setAudit(false);
        editable.setTransient(false);
        editable.setCollection(false);
        editable.setReadOnly(false);

        FieldMetadata idField = new FieldMetadata();
        idField.setFieldName("id");
        idField.setId(true);

        FieldMetadata auditField = new FieldMetadata();
        auditField.setFieldName("createdBy");
        auditField.setAudit(true);

        descriptor.setFields(List.of(editable, idField, auditField));

        List<FieldMetadata> editableFields = descriptor.getEditableFields();
        assertEquals(1, editableFields.size());
        assertEquals("name", editableFields.get(0).getFieldName());
    }

    @Test
    void getIdField_returnsIdField() {
        FieldMetadata idField = new FieldMetadata();
        idField.setFieldName("id");
        idField.setId(true);

        FieldMetadata otherField = new FieldMetadata();
        otherField.setFieldName("name");
        otherField.setId(false);

        descriptor.setFields(List.of(otherField, idField));

        FieldMetadata result = descriptor.getIdField();
        assertNotNull(result);
        assertEquals("id", result.getFieldName());
    }

    @Test
    void getIdField_returnsNull_whenNoIdField() {
        FieldMetadata otherField = new FieldMetadata();
        otherField.setFieldName("name");
        otherField.setId(false);

        descriptor.setFields(List.of(otherField));

        assertNull(descriptor.getIdField());
    }

    @Test
    void getField_findsFieldByName() {
        FieldMetadata field = new FieldMetadata();
        field.setFieldName("username");

        descriptor.setFields(List.of(field));

        FieldMetadata result = descriptor.getField("username");
        assertNotNull(result);
        assertEquals("username", result.getFieldName());
    }

    @Test
    void getField_returnsNull_whenNotFound() {
        FieldMetadata field = new FieldMetadata();
        field.setFieldName("username");

        descriptor.setFields(List.of(field));

        assertNull(descriptor.getField("nonexistent"));
    }

    @Test
    void extendsSuperModel_returnsFalse_forNonSuperModelClass() {
        descriptor.setEntityClass(String.class);
        assertFalse(descriptor.extendsSuperModel());
    }

    @Test
    void toString_containsDisplayNameAndEntityName() {
        descriptor.setDisplayName("My Entity");
        descriptor.setEntityName("MyEntity");

        String result = descriptor.toString();
        assertEquals("My Entity (MyEntity)", result);
    }

    @Test
    void fieldsDefaultToEmptyList() {
        EntityDescriptor fresh = new EntityDescriptor();
        assertNotNull(fresh.getFields());
        assertTrue(fresh.getFields().isEmpty());
    }
}

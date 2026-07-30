/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.service;

import ch.plaintext.jpa.model.EntityDescriptor;
import ch.plaintext.jpa.model.FieldMetadata;
import jakarta.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntityMetadataServiceTest {

    private EntityMetadataService service;

    @BeforeEach
    void setUp() {
        service = new EntityMetadataService();
    }

    @Entity
    @Table(name = "test_entities")
    static class TestEntity {
        @Id
        private Long id;

        @Column(nullable = false, length = 100)
        private String name;

        @Column(name = "description")
        @Lob
        private String description;

        @Transient
        private String tempValue;

        private String mandat;

        private String createdBy;

        private List<String> items;

        private static String staticField;
    }

    @Entity
    static class NoTableEntity {
        @Id
        private Long id;
    }

    @Test
    void analyzeEntity_setsEntityName() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        assertEquals("TestEntity", result.getEntityName());
    }

    @Test
    void analyzeEntity_setsDisplayName() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        assertEquals("TestEntity", result.getDisplayName());
    }

    @Test
    void analyzeEntity_setsEntityClass() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        assertEquals(TestEntity.class, result.getEntityClass());
    }

    @Test
    void analyzeEntity_detectsMandatField() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        assertTrue(result.isHasMandatField());
    }

    @Test
    void analyzeEntity_setsTableNameFromAnnotation() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        assertEquals("test_entities", result.getTableName());
    }

    @Test
    void analyzeEntity_setsTableNameFromCamelCase_whenNoTableAnnotation() {
        EntityDescriptor result = service.analyzeEntity(NoTableEntity.class);
        assertEquals("no_table_entity", result.getTableName());
    }

    @Test
    void analyzeEntity_extractsFields() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        assertFalse(result.getFields().isEmpty());
    }

    @Test
    void analyzeEntity_excludesStaticFields() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        boolean hasStatic = result.getFields().stream()
                .anyMatch(f -> f.getFieldName().equals("staticField"));
        assertFalse(hasStatic);
    }

    @Test
    void analyzeEntity_marksIdField() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        FieldMetadata idField = result.getField("id");
        assertNotNull(idField);
        assertTrue(idField.isId());
    }

    @Test
    void analyzeEntity_marksMandatField() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        FieldMetadata mandatField = result.getField("mandat");
        assertNotNull(mandatField);
        assertTrue(mandatField.isMandat());
    }

    @Test
    void analyzeEntity_marksAuditField() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        FieldMetadata auditField = result.getField("createdBy");
        assertNotNull(auditField);
        assertTrue(auditField.isAudit());
    }

    @Test
    void analyzeEntity_marksTransientField() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        FieldMetadata transientField = result.getField("tempValue");
        assertNotNull(transientField);
        assertTrue(transientField.isTransient());
    }

    @Test
    void analyzeEntity_marksLobField() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        FieldMetadata lobField = result.getField("description");
        assertNotNull(lobField);
        assertTrue(lobField.isLob());
    }

    @Test
    void analyzeEntity_readsColumnLength() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        FieldMetadata nameField = result.getField("name");
        assertNotNull(nameField);
        assertEquals(100, nameField.getMaxLength());
        assertTrue(nameField.isRequired());
    }

    @Test
    void analyzeEntity_marksIdFieldReadOnly() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        FieldMetadata idField = result.getField("id");
        assertNotNull(idField);
        assertTrue(idField.isReadOnly());
    }

    @Test
    void analyzeEntity_marksAuditFieldReadOnly() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        FieldMetadata auditField = result.getField("createdBy");
        assertNotNull(auditField);
        assertTrue(auditField.isReadOnly());
    }

    @Test
    void analyzeEntity_setsFieldDisplayName() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        FieldMetadata nameField = result.getField("name");
        assertNotNull(nameField);
        assertEquals("Name", nameField.getDisplayName());
    }

    @Test
    void analyzeEntity_setsFieldDisplayName_camelCase() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        FieldMetadata field = result.getField("createdBy");
        assertNotNull(field);
        assertEquals("Created By", field.getDisplayName());
    }

    @Test
    void analyzeEntity_marksCollectionField() {
        EntityDescriptor result = service.analyzeEntity(TestEntity.class);
        FieldMetadata itemsField = result.getField("items");
        assertNotNull(itemsField);
        assertTrue(itemsField.isCollection());
    }

    @Test
    void hasMandatField_returnsTrue_whenMandatExists() {
        assertTrue(service.hasMandatField(TestEntity.class));
    }

    @Test
    void hasMandatField_returnsFalse_whenMandatMissing() {
        assertFalse(service.hasMandatField(NoTableEntity.class));
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.model;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Descriptor for a JPA entity with all its metadata
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Data
public class EntityDescriptor implements Serializable {
    private static final long serialVersionUID = 1L;

    private String entityName;
    private String displayName;
    private Class<?> entityClass;
    private boolean hasMandatField;
    private List<FieldMetadata> fields = new ArrayList<>();
    private String tableName;

    /**
     * Gets all displayable fields for table view
     */
    public List<FieldMetadata> getDisplayFields() {
        return fields.stream()
                .filter(FieldMetadata::isDisplayable)
                .limit(10) // Limit to 10 columns for readability
                .collect(Collectors.toList());
    }

    /**
     * Gets all editable fields for form view
     */
    public List<FieldMetadata> getEditableFields() {
        return fields.stream()
                .filter(FieldMetadata::isEditable)
                .collect(Collectors.toList());
    }

    /**
     * Gets the ID field
     */
    public FieldMetadata getIdField() {
        return fields.stream()
                .filter(FieldMetadata::isId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets a field by name
     */
    public FieldMetadata getField(String fieldName) {
        return fields.stream()
                .filter(f -> f.getFieldName().equals(fieldName))
                .findFirst()
                .orElse(null);
    }

    /**
     * Checks if this entity extends SuperModel
     */
    public boolean extendsSuperModel() {
        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            if ("SuperModel".equals(current.getSimpleName())) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    @Override
    public String toString() {
        return displayName + " (" + entityName + ")";
    }
}

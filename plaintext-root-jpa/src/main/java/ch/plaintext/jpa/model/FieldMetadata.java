/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.model;

import lombok.Data;

import java.io.Serializable;

/**
 * Metadata for an entity field
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Data
public class FieldMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private String fieldName;
    private String displayName;
    private Class<?> fieldType;
    private String fieldTypeName;

    private boolean isId;
    private boolean isMandat;
    private boolean isAudit;
    private boolean isTransient;
    private boolean isCollection;
    private boolean isRequired;
    private boolean isReadOnly;
    private boolean isLob;

    private int maxLength = 255;
    private String columnDefinition;

    /**
     * Determines if this field should be displayed in tables
     */
    public boolean isDisplayable() {
        return !isTransient && !isCollection;
    }

    /**
     * Determines if this field should be editable in forms
     */
    public boolean isEditable() {
        return !isId && !isAudit && !isTransient && !isCollection && !isReadOnly;
    }

    /**
     * Gets the simple type name for UI purposes
     */
    public String getSimpleTypeName() {
        if (fieldType == null) {
            return fieldTypeName != null ? fieldTypeName : "String";
        }

        String name = fieldType.getSimpleName();

        // Map common types
        if (name.equals("LocalDateTime")) return "DateTime";
        if (name.equals("LocalDate")) return "Date";
        if (name.equals("LocalTime")) return "Time";
        if (name.equals("BigDecimal")) return "Decimal";

        return name;
    }

    /**
     * Checks if this is a numeric field
     */
    public boolean isNumeric() {
        if (fieldType == null) return false;

        return fieldType == Integer.class || fieldType == int.class ||
               fieldType == Long.class || fieldType == long.class ||
               fieldType == Float.class || fieldType == float.class ||
               fieldType == Double.class || fieldType == double.class ||
               fieldType.getName().equals("java.math.BigDecimal");
    }

    /**
     * Checks if this is a date/time field
     */
    public boolean isDateTime() {
        if (fieldType == null) return false;

        String typeName = fieldType.getName();
        return typeName.equals("java.util.Date") ||
               typeName.equals("java.time.LocalDateTime") ||
               typeName.equals("java.time.LocalDate") ||
               typeName.equals("java.time.LocalTime") ||
               typeName.equals("java.sql.Date") ||
               typeName.equals("java.sql.Timestamp");
    }

    /**
     * Checks if this is a boolean field
     */
    public boolean isBooleanType() {
        if (fieldType == null) return false;

        return fieldType == Boolean.class || fieldType == boolean.class;
    }

    /**
     * Checks if this is a text field (String)
     */
    public boolean isText() {
        if (fieldType == null) return true; // Default to text

        return fieldType == String.class;
    }
}

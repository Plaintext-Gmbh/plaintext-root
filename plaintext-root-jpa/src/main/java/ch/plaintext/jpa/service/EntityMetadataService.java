/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.service;

import ch.plaintext.jpa.model.EntityDescriptor;
import ch.plaintext.jpa.model.FieldMetadata;
import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service to analyze JPA entities using reflection
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Service
@Slf4j
public class EntityMetadataService {

    private static final Set<String> AUDIT_FIELDS = new HashSet<>(Arrays.asList(
            "createdBy", "createdDate", "lastModifiedBy", "lastModifiedDate", "deleted"
    ));

    /**
     * SECURITY (Karte 307, MITTEL): Feldnamen, die in der generischen Datenverwaltung NIE als Spalte
     * erscheinen oder editierbar sein duerfen — Chiffrate/Secrets/Hashes (z.B. {@code SecretEntry.wertEncrypted},
     * im Code als "NIE im UI anzeigen (one-way)" dokumentiert). Defense-in-Depth zusaetzlich zur
     * Entity-Ausschlussliste in EntityRegistryService. Match case-insensitiv.
     */
    private static final Set<String> SENSITIVE_FIELDS = new HashSet<>(Arrays.asList(
            "wertencrypted", "password", "passwordhash", "tokenhash", "token", "secret",
            "totpsecret", "recoverycodes", "autologinkey"
    ));

    public EntityDescriptor analyzeEntity(Class<?> entityClass) {
        EntityDescriptor descriptor = new EntityDescriptor();
        descriptor.setEntityClass(entityClass);
        descriptor.setEntityName(entityClass.getSimpleName());
        descriptor.setDisplayName(getDisplayName(entityClass));
        descriptor.setHasMandatField(hasMandatField(entityClass));
        descriptor.setFields(extractFields(entityClass));
        descriptor.setTableName(getTableName(entityClass));

        log.debug("Analyzed entity: {} with {} fields", entityClass.getSimpleName(), descriptor.getFields().size());
        return descriptor;
    }

    private List<FieldMetadata> extractFields(Class<?> entityClass) {
        List<FieldMetadata> fields = new ArrayList<>();
        Set<String> processedFields = new HashSet<>();

        Class<?> currentClass = entityClass;
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (shouldIncludeField(field) && !processedFields.contains(field.getName())) {
                    fields.add(createFieldMetadata(field));
                    processedFields.add(field.getName());
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        return fields;
    }

    private boolean shouldIncludeField(Field field) {
        int modifiers = field.getModifiers();
        // SECURITY (Karte 307, MITTEL): sensible Felder (Chiffrate/Secrets/Hashes) nie ins Daten-UI.
        if (SENSITIVE_FIELDS.contains(field.getName().toLowerCase())) {
            return false;
        }
        return !Modifier.isStatic(modifiers) &&
               !field.isSynthetic() &&
               !field.getName().startsWith("$");
    }

    private FieldMetadata createFieldMetadata(Field field) {
        FieldMetadata metadata = new FieldMetadata();
        metadata.setFieldName(field.getName());
        metadata.setDisplayName(createDisplayName(field.getName()));
        metadata.setFieldType(field.getType());
        metadata.setFieldTypeName(field.getType().getSimpleName());

        metadata.setId(field.isAnnotationPresent(Id.class));
        metadata.setMandat("mandat".equals(field.getName()));
        metadata.setAudit(AUDIT_FIELDS.contains(field.getName()));
        metadata.setTransient(field.isAnnotationPresent(Transient.class) ||
                              Modifier.isTransient(field.getModifiers()));

        // Check if collection
        metadata.setCollection(java.util.Collection.class.isAssignableFrom(field.getType()) ||
                              java.util.Map.class.isAssignableFrom(field.getType()));

        // Read-only for ID and audit fields
        metadata.setReadOnly(metadata.isId() || metadata.isAudit());

        // Column annotations
        if (field.isAnnotationPresent(Column.class)) {
            Column column = field.getAnnotation(Column.class);
            metadata.setRequired(!column.nullable());
            metadata.setMaxLength(column.length());
            metadata.setColumnDefinition(column.columnDefinition());
        }

        // LOB fields
        metadata.setLob(field.isAnnotationPresent(Lob.class));

        return metadata;
    }

    public boolean hasMandatField(Class<?> entityClass) {
        return findFieldInHierarchy(entityClass, "mandat") != null;
    }

    private Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            try {
                return currentClass.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                currentClass = currentClass.getSuperclass();
            }
        }
        return null;
    }

    private String getTableName(Class<?> entityClass) {
        if (entityClass.isAnnotationPresent(Table.class)) {
            Table table = entityClass.getAnnotation(Table.class);
            if (!table.name().isEmpty()) {
                return table.name();
            }
        }
        return camelToSnake(entityClass.getSimpleName());
    }

    private String camelToSnake(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private String getDisplayName(Class<?> entityClass) {
        return entityClass.getSimpleName();
    }

    private String createDisplayName(String fieldName) {
        String result = fieldName.replaceAll("([a-z])([A-Z])", "$1 $2");
        return result.substring(0, 1).toUpperCase() + result.substring(1);
    }
}

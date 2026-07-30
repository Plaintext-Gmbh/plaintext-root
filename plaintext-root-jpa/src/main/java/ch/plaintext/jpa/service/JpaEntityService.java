/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.service;

import ch.plaintext.framework.PlaintextRepository;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.jpa.model.FieldMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * Generic CRUD operations for all entities
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class JpaEntityService {

    private final EntityRegistryService registryService;
    private final PlaintextSecurity security;

    @SuppressWarnings("unchecked")
    public List<?> findAll(String entityName) {
        JpaRepository repository = getRepository(entityName);
        return repository.findAll();
    }

    @SuppressWarnings("unchecked")
    public List<?> findByMandat(String entityName, String mandat) {
        Object repository = registryService.getRepository(entityName);

        if (repository instanceof PlaintextRepository) {
            return ((PlaintextRepository<?>) repository).findByMandat(mandat);
        }

        try {
            Method method = repository.getClass().getMethod("findByMandat", String.class);
            Object result = method.invoke(repository, mandat);

            // Handle Optional return type
            if (result instanceof java.util.Optional) {
                java.util.Optional<?> optional = (java.util.Optional<?>) result;
                return optional.map(java.util.Collections::singletonList)
                              .orElse(java.util.Collections.emptyList());
            }

            // Handle List return type
            return (List<?>) result;
        } catch (NoSuchMethodException e) {
            // SECURITY (Karte 307, K2.1): FAIL-CLOSED. Fehlt eine mandantenscopende findByMandat-Methode,
            // darf NICHT auf findAll() zurueckgefallen werden — das lieferte einem Mandant-ADMIN saemtliche
            // Datensaetze ALLER Mandanten (z.B. Reset-Tokens). Lieber gar nichts als mandantenuebergreifend.
            log.error("SECURITY: Entity {} hat keine findByMandat-Methode — mandantenscoped Zugriff verweigert "
                    + "(kein Fallback auf findAll). Entity gehoert nicht in die mandantenscopende Datenverwaltung.", entityName);
            throw new IllegalStateException(
                    "Entity '" + entityName + "' unterstuetzt keine mandantenscopende Abfrage (findByMandat fehlt).");
        } catch (Exception e) {
            log.error("Error calling findByMandat", e);
            throw new RuntimeException("Failed to query entities", e);
        }
    }

    public Object findById(String entityName, Long id) {
        JpaRepository repository = getRepository(entityName);
        return repository.findById(id).orElse(null);
    }

    public Object save(String entityName, Object entity) {
        JpaRepository repository = getRepository(entityName);
        return repository.save(entity);
    }

    public void delete(String entityName, Long id) {
        JpaRepository repository = getRepository(entityName);
        repository.deleteById(id);
    }

    public Object createNew(String entityName) throws Exception {
        Class<?> entityClass = registryService.getEntity(entityName).getEntityClass();
        Object instance = entityClass.getDeclaredConstructor().newInstance();

        if (registryService.getEntity(entityName).isHasMandatField()) {
            setMandatField(instance, security.getMandat());
        }

        return instance;
    }

    public Object getFieldValue(Object entity, String fieldName) {
        try {
            Field field = findField(entity.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.get(entity);
            }
        } catch (Exception e) {
            log.error("Failed to get field value: {}", fieldName, e);
        }
        return null;
    }

    public String getFieldValueAsString(Object entity, FieldMetadata fieldMetadata) {
        Object value = getFieldValue(entity, fieldMetadata.getFieldName());
        if (value == null) return "";

        if (value instanceof Date) {
            return new SimpleDateFormat("dd.MM.yyyy HH:mm").format((Date) value);
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        }

        return value.toString();
    }

    public void setFieldValue(Object entity, String fieldName, Object value) {
        try {
            Field field = findField(entity.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                Object convertedValue = convertValue(value, field.getType());
                field.set(entity, convertedValue);
            }
        } catch (Exception e) {
            log.error("Failed to set field value: {}", fieldName, e);
        }
    }

    private void setMandatField(Object entity, String mandat) {
        try {
            Field field = findField(entity.getClass(), "mandat");
            if (field != null) {
                field.setAccessible(true);
                field.set(entity, mandat);
            }
        } catch (Exception e) {
            log.error("Failed to set mandat", e);
        }
    }

    private Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) return null;
        if (targetType.isInstance(value)) return value;

        if (value instanceof String) {
            String str = (String) value;
            if (str.isEmpty()) return null;

            if (targetType == Long.class || targetType == long.class) {
                return Long.parseLong(str);
            }
            if (targetType == Integer.class || targetType == int.class) {
                return Integer.parseInt(str);
            }
            if (targetType == Float.class || targetType == float.class) {
                return Float.parseFloat(str);
            }
            if (targetType == Double.class || targetType == double.class) {
                return Double.parseDouble(str);
            }
            if (targetType == Boolean.class || targetType == boolean.class) {
                return Boolean.parseBoolean(str);
            }
        }

        return value;
    }

    @SuppressWarnings("unchecked")
    private JpaRepository getRepository(String entityName) {
        Object repository = registryService.getRepository(entityName);
        if (repository == null) {
            throw new IllegalArgumentException("No repository: " + entityName);
        }
        if (!(repository instanceof JpaRepository)) {
            throw new IllegalArgumentException("Not a JpaRepository: " + entityName);
        }
        return (JpaRepository) repository;
    }
}

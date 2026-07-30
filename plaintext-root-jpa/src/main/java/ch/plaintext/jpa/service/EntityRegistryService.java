/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.service;

import ch.plaintext.jpa.model.EntityDescriptor;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.Entity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry of all manageable JPA entities
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EntityRegistryService {

    private final EntityMetadataService metadataService;
    private final ApplicationContext applicationContext;

    private final Map<String, EntityDescriptor> entityRegistry = new ConcurrentHashMap<>();
    private final Map<String, Object> repositoryRegistry = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        scanAndRegisterEntities();
    }

    private void scanAndRegisterEntities() {
        log.info("Scanning for JPA entities...");

        Map<String, ?> repositories = applicationContext.getBeansOfType(JpaRepository.class);
        log.info("Found {} JpaRepository beans", repositories.size());

        for (Map.Entry<String, ?> entry : repositories.entrySet()) {
            Object repository = entry.getValue();
            log.debug("Checking repository bean: {}", entry.getKey());

            Class<?> entityClass = getEntityClass(repository);
            if (entityClass != null) {
                log.debug("Found entity class: {}", entityClass.getName());
                if (entityClass.isAnnotationPresent(Entity.class)) {
                    registerEntity(entityClass, repository);
                } else {
                    log.debug("Skipping {} - not annotated with @Entity", entityClass.getName());
                }
            } else {
                log.debug("Could not determine entity class for repository: {}", entry.getKey());
            }
        }

        log.info("Registered {} entities", entityRegistry.size());
    }

    private Class<?> getEntityClass(Object repository) {
        Class<?> repoClass = repository.getClass();
        log.debug("Repository class: {}", repoClass.getName());

        // Try to extract from the repository's generic interfaces
        Class<?> entityClass = extractEntityClassFromInterfaces(repoClass);
        if (entityClass != null) {
            return entityClass;
        }

        // For Spring Data JPA proxies, we need to check all interfaces
        for (Class<?> iface : repoClass.getInterfaces()) {
            log.debug("Checking interface: {}", iface.getName());
            entityClass = extractEntityClassFromInterfaces(iface);
            if (entityClass != null) {
                return entityClass;
            }
        }

        return null;
    }

    private Class<?> extractEntityClassFromInterfaces(Class<?> clazz) {
        for (Type genericInterface : clazz.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) genericInterface;
                Type rawType = pt.getRawType();

                log.debug("Checking parameterized type: {}", pt);

                if (rawType instanceof Class && JpaRepository.class.isAssignableFrom((Class<?>) rawType)) {
                    Type[] typeArgs = pt.getActualTypeArguments();
                    if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                        return (Class<?>) typeArgs[0];
                    }
                }
            }
        }
        return null;
    }

    private void registerEntity(Class<?> entityClass, Object repository) {
        try {
            EntityDescriptor descriptor = metadataService.analyzeEntity(entityClass);
            String entityName = entityClass.getSimpleName();

            entityRegistry.put(entityName, descriptor);
            repositoryRegistry.put(entityName, repository);

            log.debug("Registered: {}", entityName);
        } catch (Exception e) {
            log.error("Failed to register entity: {}", entityClass.getSimpleName(), e);
        }
    }

    public List<EntityDescriptor> getAllEntities() {
        return new ArrayList<>(entityRegistry.values());
    }

    /**
     * Security-sensible Entities (Auth/Token/Audit), die NIE in der generischen mandantenscopenden
     * Datenverwaltung auftauchen duerfen (Karte 307, K2.2) — auch wenn sie ein mandat-Feld haben.
     * Ein mandat-FELD bedeutet nicht, dass das Repository eine mandantenscopende findByMandat-METHODE hat;
     * zusammen mit dem fail-closed in JpaEntityService (K2.1) wird so ein Cross-Mandant-Dump verhindert.
     * Match case-insensitiv gegen den Simple-Class-Namen.
     */
    private static final java.util.Set<String> SECURITY_SENSITIVE_ENTITIES = java.util.Set.of(
            "passwordresettoken", "registrationtoken", "magiclinktoken", "notification",
            "apitoken", "myrememberme", "myuserentity", "impersonationaudit", "usermandate",
            "secretentry", "totprecoverycode");

    public List<EntityDescriptor> getMandatAwareEntities() {
        return entityRegistry.values().stream()
                .filter(EntityDescriptor::isHasMandatField)
                .filter(d -> !isSecuritySensitive(d))
                .collect(Collectors.toList());
    }

    private static boolean isSecuritySensitive(EntityDescriptor d) {
        if (d.getEntityClass() == null) {
            return false;
        }
        return SECURITY_SENSITIVE_ENTITIES.contains(d.getEntityClass().getSimpleName().toLowerCase());
    }

    public EntityDescriptor getEntity(String entityName) {
        return entityRegistry.get(entityName);
    }

    public Object getRepository(String entityName) {
        return repositoryRegistry.get(entityName);
    }
}

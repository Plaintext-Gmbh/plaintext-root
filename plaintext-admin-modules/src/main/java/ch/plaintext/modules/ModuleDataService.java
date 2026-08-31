/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import ch.plaintext.jpa.service.JpaEntityService;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Export/import of a complete module data set as JSON (Task #016 phase 2, PR 3). Uses the
 * existing {@link JpaEntityService} (pattern {@code RootEntityBackingBean}) instead of its own
 * persistence logic — a module registers the entities worth exporting via
 * {@link ModuleDescriptor#entities()}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ModuleDataService {

    private final List<ModuleDescriptor> descriptors;
    private final JpaEntityService entityService;

    /** Result of an import: how many rows in total/successful, plus error messages per row. */
    public record ImportResult(int gesamt, int gespeichert, List<String> fehler) {
    }

    /** Exports all tables of a module as the JSON envelope {@code {module, version, tables}}. */
    public String export(String moduleId) {
        ModuleDescriptor descriptor = findDescriptor(moduleId);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("module", moduleId);
        envelope.put("version", descriptor.version());

        Map<String, List<?>> tables = new LinkedHashMap<>();
        for (Class<?> entityClass : descriptor.entities()) {
            String entityName = entityClass.getSimpleName();
            tables.put(entityName, entityService.findAll(entityName));
        }
        envelope.put("tables", tables);

        try {
            return objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Export von Modul '" + moduleId + "' fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    /**
     * Imports a previously exported module file back — existing rows are overwritten based on
     * their ID (semantics of {@link JpaEntityService#save}), new ones are created. A faulty
     * record does not abort the rest of the import (best effort, like
     * {@code RootEntityBackingBean.importEntities()}).
     */
    @Transactional
    public ImportResult importData(String moduleId, byte[] json) {
        ModuleDescriptor descriptor = findDescriptor(moduleId);
        Map<String, Class<?>> entityClassByName = descriptor.entities().stream()
                .collect(Collectors.toMap(Class::getSimpleName, c -> c));

        JsonNode root;
        try {
            root = objectMapper().readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Keine gültige Export-Datei: " + e.getMessage(), e);
        }

        String fileModule = root.path("module").asText(null);
        if (fileModule == null || !moduleId.equals(fileModule)) {
            throw new IllegalArgumentException(
                    "Datei gehört zu Modul '" + fileModule + "', erwartet wurde '" + moduleId + "'.");
        }

        JsonNode tables = root.path("tables");
        int gesamt = 0;
        int gespeichert = 0;
        List<String> fehler = new ArrayList<>();

        Iterator<String> tableNames = tables.fieldNames();
        while (tableNames.hasNext()) {
            String entityName = tableNames.next();
            Class<?> entityClass = entityClassByName.get(entityName);
            if (entityClass == null) {
                fehler.add("Unbekannte Tabelle '" + entityName + "' übersprungen (nicht Teil von Modul '" + moduleId + "').");
                continue;
            }

            List<?> rows;
            try {
                rows = objectMapper().convertValue(tables.get(entityName),
                        objectMapper().getTypeFactory().constructCollectionType(List.class, entityClass));
            } catch (Exception e) {
                fehler.add("Tabelle '" + entityName + "': " + e.getMessage());
                continue;
            }

            for (Object row : rows) {
                gesamt++;
                try {
                    entityService.save(entityName, row);
                    gespeichert++;
                } catch (Exception e) {
                    log.warn("Import: Zeile in '{}' konnte nicht gespeichert werden", entityName, e);
                    fehler.add(entityName + ": " + e.getMessage());
                }
            }
        }

        return new ImportResult(gesamt, gespeichert, fehler);
    }

    private ModuleDescriptor findDescriptor(String moduleId) {
        return descriptors.stream()
                .filter(d -> moduleId.equals(d.moduleId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Modul: " + moduleId));
    }

    /** Own instance (not shared as a bean) — configured analogously to {@code RootEntityBackingBean}. */
    private ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }
}

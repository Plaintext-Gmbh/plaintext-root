/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import ch.plaintext.jpa.service.EntityRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Clear data" for a module (Task #016 phase 2, PR 4) — with the two mandatory safety nets
 * demanded by Daniel: (1) auto export BEFORE any delete access, aborts the whole action if
 * the export fails; (2) server-side re-validated name confirmation (a disabled client button
 * is not enough — this service checks once more independently).
 *
 * <p>Deliberately only "clear data" (DELETE of all rows per entity), <b>no</b> {@code DROP TABLE}:
 * Flyway would still list the corresponding migration as "applied" and would therefore never
 * recreate the table — a DROP could only be undone with a repair action.</p>
 *
 * <p>Deliberately uses {@link JpaRepository#deleteAll()} via the repository bean already resolved
 * by {@link EntityRegistryService}, instead of raw {@code TRUNCATE}/{@code DELETE} SQL statements
 * — this stays within the established pattern of this module (JPA only, no JdbcTemplate/
 * EntityManager access in the project) and is transactional/cascade-safe like any other delete
 * operation.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ModuleDangerZoneService {

    private final List<ModuleDescriptor> descriptors;
    private final ModuleDataService moduleDataService;
    private final EntityRegistryService registryService;

    /** Result: the backup JSON created before the deletion, plus deleted rows per entity. */
    public record ClearResult(String exportJson, Map<String, Integer> geloeschtProEntity) {
    }

    /**
     * Clears all tables of a module. {@code bestaetigungsName} has to match the module's display
     * name exactly (case-sensitive) — otherwise it aborts without touching anything.
     */
    @Transactional
    public ClearResult clearData(String moduleId, String bestaetigungsName) {
        ModuleDescriptor descriptor = findDescriptor(moduleId);

        if (bestaetigungsName == null || !bestaetigungsName.equals(descriptor.displayName())) {
            throw new IllegalArgumentException(
                    "Bestätigung fehlgeschlagen: Modulname stimmt nicht überein.");
        }

        // Safety net 1: auto export FIRST — if that fails, the database stays untouched
        // (export() throws on an error before any repository method has been called).
        String exportJson = moduleDataService.export(moduleId);

        Map<String, Integer> geloescht = new LinkedHashMap<>();
        for (Class<?> entityClass : descriptor.entities()) {
            String entityName = entityClass.getSimpleName();
            Object repository = registryService.getRepository(entityName);
            if (!(repository instanceof JpaRepository<?, ?> jpaRepository)) {
                throw new IllegalStateException("Kein Repository für Entity '" + entityName + "' gefunden.");
            }
            long count = jpaRepository.count();
            jpaRepository.deleteAll();
            geloescht.put(entityName, (int) count);
        }

        log.warn("Modul '{}' Daten geleert (Bestätigung '{}' korrekt): {}", moduleId, bestaetigungsName, geloescht);
        return new ClearResult(exportJson, geloescht);
    }

    private ModuleDescriptor findDescriptor(String moduleId) {
        return descriptors.stream()
                .filter(d -> moduleId.equals(d.moduleId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Modul: " + moduleId));
    }
}

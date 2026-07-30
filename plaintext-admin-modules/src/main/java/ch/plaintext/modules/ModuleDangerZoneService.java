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
 * „Daten leeren" für ein Modul (Task #016 Phase 2, PR 4) — mit den beiden von Daniel verlangten
 * Pflicht-Schutznetzen: (1) Auto-Export VOR jedem Löschzugriff, bricht die ganze Aktion ab, wenn
 * der Export fehlschlägt; (2) serverseitig re-validierte Namens-Bestätigung (ein deaktivierter
 * Client-Button reicht nicht — dieser Service prüft unabhängig noch einmal).
 *
 * <p>Bewusst nur „Daten leeren" (DELETE aller Zeilen je Entity), <b>kein</b> {@code DROP TABLE}:
 * Flyway würde die zugehörige Migration weiterhin als „applied" führen, die Tabelle also nie neu
 * anlegen — ein DROP wäre nur mit einer Repair-Aktion rückgängig zu machen.</p>
 *
 * <p>Nutzt bewusst {@link JpaRepository#deleteAll()} über die von {@link EntityRegistryService}
 * bereits aufgelöste Repository-Bean, statt roher {@code TRUNCATE}/{@code DELETE}-SQL-Statements
 * — bleibt damit im etablierten Muster dieses Moduls (nur JPA, keine JdbcTemplate/EntityManager-
 * Zugriffe im Projekt) und ist transaktional/Cascade-sicher wie jede andere Lösch-Operation.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ModuleDangerZoneService {

    private final List<ModuleDescriptor> descriptors;
    private final ModuleDataService moduleDataService;
    private final EntityRegistryService registryService;

    /** Ergebnis: das vor dem Löschen erzeugte Backup-JSON, plus gelöschte Zeilen je Entity. */
    public record ClearResult(String exportJson, Map<String, Integer> geloeschtProEntity) {
    }

    /**
     * Leert alle Tabellen eines Moduls. {@code bestaetigungsName} muss exakt dem Anzeigenamen des
     * Moduls entsprechen (case-sensitiv) — sonst wird abgebrochen, ohne irgendetwas anzufassen.
     */
    @Transactional
    public ClearResult clearData(String moduleId, String bestaetigungsName) {
        ModuleDescriptor descriptor = findDescriptor(moduleId);

        if (bestaetigungsName == null || !bestaetigungsName.equals(descriptor.displayName())) {
            throw new IllegalArgumentException(
                    "Bestätigung fehlgeschlagen: Modulname stimmt nicht überein.");
        }

        // Schutznetz 1: Auto-Export ZUERST — schlägt das fehl, bleibt die Datenbank unangetastet
        // (export() wirft bei einem Fehler, bevor irgendeine Repository-Methode aufgerufen wurde).
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

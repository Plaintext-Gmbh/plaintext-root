/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.web;

import ch.plaintext.boot.menu.MenuAnnotation;
import ch.plaintext.jpa.model.EntityDescriptor;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
import org.primefaces.model.file.UploadedFile;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Root Entity Management Backing Bean
 * Allows root users to manage all entities across all mandates.
 *
 * <p>The entire management logic lives in {@link AbstractEntityBackingBean}; only what sets the
 * Root apart from the Admin is here: all entities across all tenants, plus JSON export/import.
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Component
@Named("rootEntityBackingBean")
@Getter
@Setter
@Slf4j
@Scope(scopeName = "session")
@MenuAnnotation(
    title = "Datenverwaltung",
    link = "rootentities.html",
    parent = "Root",
    order = 100,
    icon = "pi pi-database",
    roles = {"ROOT"}
)
public class RootEntityBackingBean extends AbstractEntityBackingBean {
    private static final long serialVersionUID = 1L;

    private UploadedFile uploadedFile;
    private StreamedContent exportFile;
    private ObjectMapper objectMapper;

    @PostConstruct
    @Override
    public void initThis() {
        super.initThis();
        initializeObjectMapper();
    }

    @Override
    protected List<EntityDescriptor> ladeVerfuegbareEntities() {
        log.info("Loading all entities for Root");
        return registryService.getAllEntities();
    }

    @Override
    protected List<?> ladeEntities(String entityName) {
        return entityService.findAll(entityName);
    }

    private void initializeObjectMapper() {
        objectMapper = exportMapper();
    }

    /** Paket-sichtbar fuer den Formatvertrag (Karte 1423). */
    static ObjectMapper exportMapper() {
        // Karte 1423: Jackson 3 mit den Vorgaben von Jackson 2; das Exportformat ist ein Vertrag
        // (RootEntityExportVertragTest). java.time ist in Jackson 3 eingebaut.
        // Hibernate module disabled (not yet compatible with Hibernate 7/SB4)
        return JsonMapper.builderWithJackson2Defaults()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(SerializationFeature.FAIL_ON_SELF_REFERENCES)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                // Set visibility to ensure all fields are serialized
                .changeDefaultVisibility(v -> v.withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                        .withVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY))
                .build();
    }

    /**
     * Export entities to JSON file
     */
    public void exportEntities() {
        EntityDescriptor selectedEntityType = getSelectedEntityType();
        if (selectedEntityType == null) {
            addErrorMessage("Fehler", "Bitte wählen Sie zuerst einen Entitätstyp aus.");
            return;
        }

        try {
            log.info("Exporting entities for type: {}", selectedEntityType.getEntityName());

            // Load all entities for the selected type
            List<?> entitiesToExport = entityService.findAll(selectedEntityType.getEntityName());

            if (entitiesToExport.isEmpty()) {
                addErrorMessage("Fehler", "Keine Daten zum Exportieren vorhanden.");
                return;
            }

            // Serialize to JSON
            String jsonContent = objectMapper.writeValueAsString(entitiesToExport);

            // Create filename with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = selectedEntityType.getEntityName() + "_export_" + timestamp + ".json";

            // Create download stream
            InputStream stream = new ByteArrayInputStream(jsonContent.getBytes(StandardCharsets.UTF_8));
            exportFile = DefaultStreamedContent.builder()
                    .name(filename)
                    .contentType("application/json")
                    .stream(() -> stream)
                    .build();

            log.info("Successfully exported {} entities to {}", entitiesToExport.size(), filename);
            addInfoMessage("Export erfolgreich", entitiesToExport.size() + " Einträge exportiert.");

        } catch (Exception e) {
            log.error("Error exporting entities", e);
            addErrorMessage("Fehler beim Export", e.getMessage());
        }
    }

    /**
     * Import entities from JSON file
     */
    @Transactional
    public void importEntities() {
        EntityDescriptor selectedEntityType = getSelectedEntityType();
        if (selectedEntityType == null) {
            addErrorMessage("Fehler", "Bitte wählen Sie zuerst einen Entitätstyp aus.");
            return;
        }

        if (uploadedFile == null) {
            addErrorMessage("Fehler", "Bitte wählen Sie eine JSON-Datei aus.");
            return;
        }

        try {
            log.info("Importing entities for type: {}", selectedEntityType.getEntityName());

            // Read uploaded file content
            InputStream inputStream = uploadedFile.getInputStream();
            String jsonContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            // Get entity class
            Class<?> entityClass = selectedEntityType.getEntityClass();

            // Deserialize JSON to entity list
            List<?> importedEntities = objectMapper.readValue(
                    jsonContent,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, entityClass)
            );

            if (importedEntities.isEmpty()) {
                addErrorMessage("Fehler", "Die importierte Datei enthält keine Daten.");
                return;
            }

            // Save all entities (will update existing or create new based on ID)
            int savedCount = 0;
            for (Object entity : importedEntities) {
                try {
                    entityService.save(selectedEntityType.getEntityName(), entity);
                    savedCount++;
                } catch (Exception e) {
                    log.warn("Failed to save entity during import", e);
                    // Continue with next entity
                }
            }

            // Reload entities to show updated data
            loadEntities();

            log.info("Successfully imported {} of {} entities", savedCount, importedEntities.size());
            addInfoMessage("Import erfolgreich",
                    savedCount + " von " + importedEntities.size() + " Einträgen importiert.");

        } catch (Exception e) {
            log.error("Error importing entities", e);
            addErrorMessage("Fehler beim Import", "Import fehlgeschlagen: " + e.getMessage());
            // Transaction will rollback automatically due to @Transactional
        }
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.menu.MenuAnnotation;
import ch.plaintext.jpa.model.EntityDescriptor;
import ch.plaintext.jpa.model.FieldMetadata;
import ch.plaintext.jpa.service.EntityRegistryService;
import ch.plaintext.jpa.service.JpaEntityService;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
import org.primefaces.model.file.UploadedFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root Entity Management Backing Bean
 * Allows root users to manage all entities across all mandates
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Component
@Named("rootEntityBackingBean")
@Data
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
public class RootEntityBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    private EntityRegistryService registryService;

    @Autowired
    private JpaEntityService entityService;

    @Autowired
    private PlaintextSecurity plaintextSecurity;

    private List<EntityDescriptor> availableEntities = new ArrayList<>();
    private EntityDescriptor selectedEntityType;
    private List<?> entities = new ArrayList<>();
    private Object selectedEntity;
    private boolean editMode;
    private Map<String, Object> fieldValues = new HashMap<>();
    private List<String> allMandate = new ArrayList<>();
    private UploadedFile uploadedFile;
    private StreamedContent exportFile;
    private ObjectMapper objectMapper;

    @PostConstruct
    public void initThis() {
        loadAvailableEntities();
        loadAllMandate();
        initializeObjectMapper();
    }

    private void initializeObjectMapper() {
        objectMapper = new ObjectMapper();

        // Hibernate module disabled (not yet compatible with Hibernate 7/SB4)
        // TODO: Re-enable when jackson-datatype-hibernate7 is released

        // Register JavaTime module for date/time handling
        objectMapper.registerModule(new JavaTimeModule());

        // Serialization features
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        objectMapper.disable(SerializationFeature.FAIL_ON_SELF_REFERENCES);

        // Deserialization features
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        objectMapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);

        // Set visibility to ensure all fields are serialized
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    private void loadAllMandate() {
        allMandate.clear();
        allMandate.addAll(plaintextSecurity.getAllMandate());
        log.info("Loaded {} mandate for dropdown", allMandate.size());
    }

    public void loadAvailableEntities() {
        log.info("Loading all entities for Root");
        availableEntities = registryService.getAllEntities();
        log.info("Found {} entities", availableEntities.size());
    }

    public void onEntityTypeSelected(jakarta.faces.event.ValueChangeEvent event) {
        selectedEntityType = (EntityDescriptor) event.getNewValue();
        log.info("Entity type selected: {}", selectedEntityType != null ? selectedEntityType.getEntityName() : "null");
        loadEntities();
    }

    public void loadEntities() {
        if (selectedEntityType == null) {
            entities.clear();
            return;
        }

        log.info("Loading all entities for {}", selectedEntityType.getEntityName());

        try {
            entities = entityService.findAll(selectedEntityType.getEntityName());
            log.info("Loaded {} entities", entities.size());
        } catch (Exception e) {
            log.error("Error loading entities", e);
            addErrorMessage("Fehler beim Laden der Daten", e.getMessage());
            entities.clear();
        }
    }

    public void newEntity() {
        if (selectedEntityType == null) {
            addErrorMessage("Fehler", "Bitte wählen Sie zuerst einen Entitätstyp aus.");
            return;
        }

        try {
            selectedEntity = entityService.createNew(selectedEntityType.getEntityName());
            editMode = true;
            initializeFieldValues();
            log.debug("Created new entity: {}", selectedEntityType.getEntityName());
        } catch (Exception e) {
            log.error("Error creating new entity", e);
            addErrorMessage("Fehler beim Erstellen", e.getMessage());
        }
    }

    public void selectEntity() {
        if (selectedEntity != null) {
            editMode = true;
            initializeFieldValues();
            log.debug("Selected entity for editing: {}", selectedEntity);
        }
    }

    private void initializeFieldValues() {
        fieldValues.clear();
        if (selectedEntity == null || selectedEntityType == null) {
            return;
        }

        // Load current values from entity into the map
        for (FieldMetadata field : selectedEntityType.getEditableFields()) {
            Object value = entityService.getFieldValue(selectedEntity, field.getFieldName());
            fieldValues.put(field.getFieldName(), value);
            log.debug("Initialized field {} with value: {}", field.getFieldName(), value);
        }
    }

    public void clearSelection() {
        selectedEntity = null;
        editMode = false;
        fieldValues.clear();
        log.debug("Cleared entity selection");
    }

    public void saveEntity() {
        if (selectedEntity == null || selectedEntityType == null) {
            addErrorMessage("Fehler", "Keine Entität ausgewählt.");
            return;
        }

        try {
            // Copy values from map back to entity
            for (FieldMetadata field : selectedEntityType.getEditableFields()) {
                Object value = fieldValues.get(field.getFieldName());
                entityService.setFieldValue(selectedEntity, field.getFieldName(), value);
                log.debug("Set field {} to value: {}", field.getFieldName(), value);
            }

            Object saved = entityService.save(selectedEntityType.getEntityName(), selectedEntity);
            addInfoMessage("Erfolg", "Daten wurden gespeichert.");
            loadEntities();
            clearSelection();
        } catch (Exception e) {
            log.error("Error saving entity", e);
            addErrorMessage("Fehler beim Speichern", e.getMessage());
        }
    }

    public void deleteEntity() {
        if (selectedEntity == null || selectedEntityType == null) {
            addErrorMessage("Fehler", "Keine Entität ausgewählt.");
            return;
        }

        try {
            Long id = (Long) entityService.getFieldValue(selectedEntity, selectedEntityType.getIdField().getFieldName());
            if (id == null) {
                addErrorMessage("Fehler", "Entität hat keine ID.");
                return;
            }

            entityService.delete(selectedEntityType.getEntityName(), id);
            addInfoMessage("Erfolg", "Daten wurden gelöscht.");
            loadEntities();
            clearSelection();
        } catch (Exception e) {
            log.error("Error deleting entity", e);
            addErrorMessage("Fehler beim Löschen", e.getMessage());
        }
    }

    public String getFieldValue(Object entity, FieldMetadata field) {
        return entityService.getFieldValueAsString(entity, field);
    }

    public Object getFieldValueForEdit(FieldMetadata field) {
        if (selectedEntity == null) {
            return null;
        }
        return entityService.getFieldValue(selectedEntity, field.getFieldName());
    }

    public void setFieldValueForEdit(FieldMetadata field, Object value) {
        if (selectedEntity == null) {
            return;
        }
        entityService.setFieldValue(selectedEntity, field.getFieldName(), value);
    }

    public List<FieldMetadata> getDisplayFields() {
        if (selectedEntityType == null) {
            return new ArrayList<>();
        }
        return selectedEntityType.getDisplayFields();
    }

    public List<FieldMetadata> getEditableFields() {
        if (selectedEntityType == null) {
            return new ArrayList<>();
        }
        return selectedEntityType.getEditableFields();
    }

    public Object getEntityId(Object entity) {
        if (entity == null || selectedEntityType == null) {
            return null;
        }
        return entityService.getFieldValue(entity, selectedEntityType.getIdField().getFieldName());
    }

    /**
     * Export entities to JSON file
     */
    public void exportEntities() {
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

    private void addInfoMessage(String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, summary, detail));
    }

    private void addErrorMessage(String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, summary, detail));
    }
}

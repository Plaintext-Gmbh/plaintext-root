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
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin Entity Management Backing Bean
 * Allows administrators to manage entities for their own mandat
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Component
@Named("adminEntityBackingBean")
@Data
@Slf4j
@Scope(scopeName = "session")
@MenuAnnotation(
    title = "Datenverwaltung",
    link = "adminentities.html",
    parent = "Admin",
    order = 100,
    icon = "pi pi-database",
    roles = {"ADMIN", "ROOT"}
)
public class AdminEntityBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    private PlaintextSecurity plaintextSecurity;

    @Autowired
    private transient EntityRegistryService registryService;

    @Autowired
    private transient JpaEntityService entityService;

    private List<EntityDescriptor> availableEntities = new ArrayList<>();
    private EntityDescriptor selectedEntityType;
    private List<?> entities = new ArrayList<>();
    private Object selectedEntity;
    private boolean editMode;
    private Map<String, Object> fieldValues = new HashMap<>();
    private List<String> allMandate = new ArrayList<>();

    @PostConstruct
    public void initThis() {
        loadAvailableEntities();
        loadAllMandate();
    }

    private void loadAllMandate() {
        allMandate.clear();
        allMandate.addAll(plaintextSecurity.getAllMandate());
        log.info("Loaded {} mandate for dropdown", allMandate.size());
    }

    public void loadAvailableEntities() {
        log.info("Loading mandat-aware entities for Admin");
        availableEntities = registryService.getMandatAwareEntities();
        log.info("Found {} mandat-aware entities", availableEntities.size());
    }

    public void onEntityTypeSelected(jakarta.faces.event.ValueChangeEvent event) {
        selectedEntityType = (EntityDescriptor) event.getNewValue();
        log.info("Entity type selected: {}", selectedEntityType != null ? selectedEntityType.getEntityName() : "null");
        loadEntities();
    }

    /**
     * Ajax-Listener der Typ-Auswahl (Auftrag Daniel, 29.08.2026). Der neue Wert steht beim Aufruf
     * bereits in {@code selectedEntityType} (UPDATE_MODEL_VALUES laeuft vor dem Listener); hier
     * wird nur die Auswahl des alten Typs verworfen und die Liste nachgeladen.
     */
    public void entityTypeChanged() {
        log.info("Entity type selected: {}", selectedEntityType != null ? selectedEntityType.getEntityName() : "null");
        selectedEntity = null;
        editMode = false;
        fieldValues.clear();
        loadEntities();
    }

    public void loadEntities() {
        if (selectedEntityType == null) {
            // Neue Liste statt clear(): findAll() kann eine unveraenderliche Liste liefern.
            entities = new ArrayList<>();
            return;
        }

        String mandat = getMandat();
        log.info("Loading entities for {} and mandat {}", selectedEntityType.getEntityName(), mandat);

        try {
            entities = entityService.findByMandat(selectedEntityType.getEntityName(), mandat);
            log.info("Loaded {} entities", entities.size());
        } catch (Exception e) {
            log.error("Error loading entities", e);
            addErrorMessage("Fehler beim Laden der Daten", e.getMessage());
            entities = new ArrayList<>();
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

            // SECURITY (Karte 307, MITTEL): ein Nicht-ROOT-Admin darf Datensaetze NUR im EIGENEN Mandanten
            // speichern. Der Mandant wird serverseitig auf den eigenen erzwungen — unabhaengig davon, was
            // das (aus getAllMandate befuellte) Dropdown geliefert hat. So kann niemand in fremde
            // Mandanten schreiben. Nur ROOT darf den Mandanten frei setzen.
            if (selectedEntityType.isHasMandatField() && !plaintextSecurity.ifGranted("ROLE_root")) {
                entityService.setFieldValue(selectedEntity, "mandat", getMandat());
                log.debug("Non-root: mandat serverseitig auf {} erzwungen", getMandat());
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

    private String getMandat() {
        if (plaintextSecurity == null) {
            return "1";
        }
        return plaintextSecurity.getMandat();
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

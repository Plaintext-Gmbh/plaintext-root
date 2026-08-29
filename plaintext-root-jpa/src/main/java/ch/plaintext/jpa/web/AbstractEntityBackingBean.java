/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.jpa.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.jpa.model.EntityDescriptor;
import ch.plaintext.jpa.model.FieldMetadata;
import ch.plaintext.jpa.service.EntityRegistryService;
import ch.plaintext.jpa.service.JpaEntityService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemeinsame Basis der generischen Datenverwaltung ({@code rootentities.html} fuer ROOT ueber alle
 * Mandanten, {@code adminentities.html} fuer ADMIN im eigenen Mandanten).
 *
 * <p>Zustandsbericht 29.08.2026 (Paket R2): {@link RootEntityBackingBean} und
 * {@link AdminEntityBackingBean} waren zwei Kopien mit 162 identischen Zeilen — Auswahl, Laden,
 * Bearbeiten-Dialog, Speichern, Loeschen, Feldzugriff, Meldungen. Jeder Fix musste zweimal gemacht
 * werden und wurde es nicht immer (der Ajax-Listener der Typ-Auswahl kam am selben Tag in beide).
 * Jetzt steht die Logik einmal hier; die Unterklassen liefern nur noch, was wirklich verschieden
 * ist:
 * <ul>
 *   <li>{@link #ladeVerfuegbareEntities()} — alle Entitaeten (Root) bzw. nur mandantenfaehige (Admin);</li>
 *   <li>{@link #ladeEntities(String)} — {@code findAll} (Root) bzw. {@code findByMandat} (Admin);</li>
 *   <li>{@link #vorSpeichern(Object)} — Admin erzwingt serverseitig den eigenen Mandanten (Karte 307);</li>
 *   <li>Rollen/Menue ueber {@code @MenuAnnotation} der Unterklasse; Export/Import nur in Root.</li>
 * </ul>
 * Die beiden XHTML teilen sich aus demselben Grund {@code /includes/entityverwaltung.xhtml}.
 *
 * <p>Session-scoped, Felder der Dienste {@code transient} (Karte 915); {@code plaintextSecurity}
 * ist ein Scoped-Proxy und bleibt absichtlich nicht-transient wie zuvor.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Getter
@Setter
@Slf4j
public abstract class AbstractEntityBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    protected transient EntityRegistryService registryService;

    @Autowired
    protected transient JpaEntityService entityService;

    @Autowired
    protected PlaintextSecurity plaintextSecurity;

    private List<EntityDescriptor> availableEntities = new ArrayList<>();
    private EntityDescriptor selectedEntityType;
    private List<?> entities = new ArrayList<>();
    private Object selectedEntity;
    private boolean editMode;
    private Map<String, Object> fieldValues = new HashMap<>();
    private List<String> allMandate = new ArrayList<>();

    /** Welche Entitaetstypen der Benutzer verwalten darf. */
    protected abstract List<EntityDescriptor> ladeVerfuegbareEntities();

    /** Die Datensaetze des gewaehlten Typs — mit oder ohne Mandanten-Einschraenkung. */
    protected abstract List<?> ladeEntities(String entityName);

    /**
     * Haken unmittelbar vor {@code entityService.save(...)}: die Feldwerte aus dem Dialog sind
     * bereits in die Entitaet uebernommen. Standard: nichts.
     */
    protected void vorSpeichern(Object entity) {
        // Unterklassen (Admin: Mandant erzwingen)
    }

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
        availableEntities = ladeVerfuegbareEntities();
        log.info("Found {} entities for {}", availableEntities.size(), getClass().getSimpleName());
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

        log.info("Loading entities for {}", selectedEntityType.getEntityName());

        try {
            entities = ladeEntities(selectedEntityType.getEntityName());
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

            vorSpeichern(selectedEntity);

            entityService.save(selectedEntityType.getEntityName(), selectedEntity);
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

    protected String getMandat() {
        if (plaintextSecurity == null) {
            return "1";
        }
        return plaintextSecurity.getMandat();
    }

    protected void addInfoMessage(String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, summary, detail));
    }

    protected void addErrorMessage(String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, summary, detail));
    }
}

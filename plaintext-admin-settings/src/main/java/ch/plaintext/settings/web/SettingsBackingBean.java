/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.entity.Setting;
import ch.plaintext.settings.service.SettingsServiceImpl;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Component
@Scope("session")
@Getter
@Setter
@Slf4j
public class SettingsBackingBean implements Serializable {

    private final SettingsServiceImpl service;
    private final PlaintextSecurity security;

    private List<Setting> settings;
    private Setting selected;
    private String searchFilter;
    private boolean root;

    public SettingsBackingBean(SettingsServiceImpl service, PlaintextSecurity security) {
        this.service = service;
        this.security = security;
    }

    /**
     * preRenderView-Listener (session-scoped statt @ViewScoped): setzt die Rolle, sperrt Nicht-ROOT aus
     * (Redirect) und lädt die Daten FRISCH bei jedem Seitenaufruf (GET). Der isPostback-Guard verhindert
     * das Neuladen bei jedem Ajax-Postback. Ersetzt das frühere @PostConstruct init() + checkAccess().
     */
    public void onLoad() {
        root = security.ifGranted("ROLE_ROOT");
        if (!root) {
            try {
                FacesContext.getCurrentInstance().getExternalContext().redirect("/index.xhtml");
            } catch (Exception e) {
                log.error("Redirect failed", e);
            }
            return;
        }
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        loadData();
    }

    private void loadData() {
        try {
            if (root) {
                settings = service.getAllSettings(security.getMandat());
            } else {
                settings = new ArrayList<>();
            }
        } catch (Exception e) {
            log.error("Error loading settings", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Daten konnten nicht geladen werden");
        }
    }

    public void select() {
        // Selected in UI
    }

    public void clearSelection() {
        selected = null;
    }

    public void newSetting() {
        selected = new Setting();
        selected.setKey("");
        selected.setMandat(security.getMandat());
        selected.setValue("");
        selected.setValueType("STRING");
        selected.setDescription("");
    }

    // Helper methods for type-specific values
    public Boolean getBooleanValue() {
        if (selected == null || selected.getValue() == null) {
            return false;
        }
        return Boolean.parseBoolean(selected.getValue());
    }

    public void setBooleanValue(Boolean value) {
        if (selected != null) {
            selected.setValue(value != null ? value.toString() : "false");
        }
    }

    public java.time.LocalDateTime getDateValue() {
        if (selected == null || selected.getValue() == null || selected.getValue().trim().isEmpty()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(selected.getValue(), java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("Cannot parse date value: {}", selected.getValue());
            return null;
        }
    }

    public void setDateValue(java.time.LocalDateTime value) {
        if (selected != null) {
            selected.setValue(value != null ? value.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "");
        }
    }

    public void save() {
        if (selected == null) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Einstellung ausgewählt");
            return;
        }

        if (selected.getKey() == null || selected.getKey().trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Schlüssel ist erforderlich");
            return;
        }

        if (selected.getMandat() == null || selected.getMandat().trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Mandat ist erforderlich");
            return;
        }

        try {
            service.setSetting(selected.getKey(), selected.getMandat(), selected.getValue(),
                             selected.getValueType(), selected.getDescription());
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Einstellung gespeichert");
            loadData();
        } catch (Exception e) {
            log.error("Error saving setting", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Speichern fehlgeschlagen");
        }
    }

    public void delete() {
        if (selected == null) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Einstellung ausgewählt");
            return;
        }

        try {
            service.deleteSetting(selected.getKey(), selected.getMandat());
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Einstellung gelöscht");
            selected = null;
            loadData();
        } catch (Exception e) {
            log.error("Error deleting setting", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Löschen fehlgeschlagen");
        }
    }

    public List<Setting> getFilteredSettings() {
        if (searchFilter == null || searchFilter.trim().isEmpty()) {
            return settings;
        }
        String filter = searchFilter.toLowerCase();
        return settings.stream()
                .filter(s -> s.getKey().toLowerCase().contains(filter) ||
                           (s.getValue() != null && s.getValue().toLowerCase().contains(filter)) ||
                           (s.getDescription() != null && s.getDescription().toLowerCase().contains(filter)))
                .toList();
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }

    public List<String> getAllMandate() {
        return new ArrayList<>(security.getAllMandate());
    }

    public List<String> getValueTypes() {
        return List.of("STRING", "INTEGER", "BOOLEAN", "DATE", "LIST");
    }
}

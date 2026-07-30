/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.web;

import ch.plaintext.MenuRegistry;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.i18n.entity.I18nTranslation;
import ch.plaintext.i18n.service.I18nService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.file.UploadedFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
@Scope("session")
@Getter
@Setter
@Slf4j
public class I18nBackingBean implements Serializable {

    private final I18nService i18nService;
    private final PlaintextSecurity security;

    @Autowired(required = false)
    private transient MenuRegistry menuRegistry;

    private List<I18nTranslation> translations;
    private I18nTranslation selected;
    private String filterLanguage;

    // Fields for new translation dialog
    private String newDefaultLabel;
    private String newLanguageCode;
    private String newTranslatedText;

    private boolean root;

    public I18nBackingBean(I18nService i18nService, PlaintextSecurity security) {
        this.i18nService = i18nService;
        this.security = security;
    }

    /**
     * preRenderView-Listener (session-scoped statt @ViewScoped): setzt die Rolle, sperrt Nicht-ROOT aus
     * und lädt die Übersetzungsliste FRISCH bei jedem Seitenaufruf (GET). Der isPostback-Guard verhindert
     * das Neuladen bei jedem Ajax-Postback, damit der Bearbeitungszustand erhalten bleibt.
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
            if (filterLanguage != null && !filterLanguage.trim().isEmpty()) {
                translations = i18nService.getTranslationsByLanguage(filterLanguage);
            } else {
                translations = i18nService.getAllTranslations();
            }
        } catch (Exception e) {
            log.error("Error loading translations", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Daten konnten nicht geladen werden");
            translations = new ArrayList<>();
        }
    }

    public void filterByLanguage() {
        loadData();
    }

    public void clearFilter() {
        filterLanguage = null;
        loadData();
    }

    public void select() {
        // Selected in UI
    }

    public void clearSelection() {
        selected = null;
    }

    public void prepareNew() {
        newDefaultLabel = "";
        newLanguageCode = "";
        newTranslatedText = "";
    }

    public void saveNew() {
        if (newDefaultLabel == null || newDefaultLabel.trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Default Label ist erforderlich");
            return;
        }
        if (newLanguageCode == null || newLanguageCode.trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Sprache ist erforderlich");
            return;
        }
        if (newTranslatedText == null || newTranslatedText.trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Übersetzung ist erforderlich");
            return;
        }

        try {
            i18nService.saveTranslation(newDefaultLabel.trim(), newLanguageCode.trim(), newTranslatedText.trim());
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Übersetzung gespeichert");
            loadData();
        } catch (Exception e) {
            log.error("Error saving translation", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Speichern fehlgeschlagen");
        }
    }

    public void saveSelected() {
        if (selected == null) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Übersetzung ausgewählt");
            return;
        }

        try {
            i18nService.saveTranslation(selected.getDefaultLabel(), selected.getLanguageCode(), selected.getTranslatedText());
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Übersetzung aktualisiert");
            loadData();
        } catch (Exception e) {
            log.error("Error updating translation", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Aktualisierung fehlgeschlagen");
        }
    }

    public void delete() {
        if (selected == null) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Übersetzung ausgewählt");
            return;
        }

        try {
            i18nService.deleteTranslation(selected.getId());
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Übersetzung gelöscht");
            selected = null;
            loadData();
        } catch (Exception e) {
            log.error("Error deleting translation", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Löschen fehlgeschlagen");
        }
    }

    public void clearCache() {
        i18nService.clearCache();
        addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Cache geleert und neu geladen");
    }

    public List<String> getAvailableLanguages() {
        return i18nService.getAvailableLanguages();
    }

    public List<String> getAllMenuLabels() {
        if (menuRegistry != null) {
            return menuRegistry.getAllMenuTitles();
        }
        return new ArrayList<>();
    }

    /**
     * Handles CSV file upload from the PrimeFaces fileUpload component.
     * CSV format: defaultLabel;languageCode;translatedText (semicolon-separated)
     */
    public void handleCsvImport(FileUploadEvent event) {
        UploadedFile file = event.getFile();
        if (file == null || file.getSize() == 0) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Datei ist leer");
            return;
        }

        int imported = 0;
        int skipped = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Skip empty lines, comments, and header
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (lineNumber == 1 && line.startsWith("defaultLabel")) {
                    continue;
                }

                String[] parts = line.split(";", 3);
                if (parts.length < 3) {
                    log.warn("CSV import: line {} has {} columns, expected 3", lineNumber, parts.length);
                    skipped++;
                    continue;
                }

                String defaultLabel = parts[0].trim();
                String languageCode = parts[1].trim();
                String translatedText = parts[2].trim();

                // Remove surrounding quotes if present
                defaultLabel = unquoteCsv(defaultLabel);
                languageCode = unquoteCsv(languageCode);
                translatedText = unquoteCsv(translatedText);

                if (defaultLabel.isEmpty() || languageCode.isEmpty()) {
                    skipped++;
                    continue;
                }

                try {
                    i18nService.saveTranslation(defaultLabel, languageCode, translatedText);
                    imported++;
                } catch (Exception e) {
                    log.error("CSV import error at line {}: {}", lineNumber, e.getMessage());
                    skipped++;
                }
            }

            addMessage(FacesMessage.SEVERITY_INFO, "Import abgeschlossen",
                    imported + " importiert, " + skipped + " übersprungen");
            loadData();

        } catch (Exception e) {
            log.error("Error importing CSV file", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "CSV Import fehlgeschlagen: " + e.getMessage());
        }
    }

    private String unquoteCsv(String value) {
        if (value != null && value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value;
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }
}

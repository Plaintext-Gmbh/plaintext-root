/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.web;

import ch.plaintext.modules.ModuleDangerZoneService;
import ch.plaintext.modules.ModuleDataService;
import ch.plaintext.modules.ModuleService;
import ch.plaintext.modules.ModuleView;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
import org.primefaces.model.file.UploadedFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Backing Bean der Modul-Verwaltung (module.html): Liste anzeigen + ein-/ausschalten sowie
 * Export/Import der Moduldaten als JSON (Task #016 Phase 2, PR 3).
 */
@Slf4j
@Scope("session")
@Component
@Data
public class ModulesBackingBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Autowired
    private transient ModuleService moduleService;

    @Autowired
    private transient ModuleDataService moduleDataService;

    @Autowired
    private transient ModuleDangerZoneService dangerZoneService;

    private List<ModuleView> module = new ArrayList<>();

    // ── Export (Download) ──────────────────────────────────
    private StreamedContent exportFile;

    // ── Import (Upload) ────────────────────────────────────
    private String importModuleId;
    private String importModuleDisplayName;
    private transient byte[] importBytes;
    private String importFileName;

    // ── Danger-Zone (Daten leeren) ─────────────────────────
    private String clearModuleId;
    private String clearModuleDisplayName;
    private String clearBestaetigung;

    public void load() {
        module = moduleService.list();
    }

    /** Ajax-Listener des Ein-/Aus-Schalters: {@code m.enabled} ist bereits gesetzt, persistieren. */
    public void toggle(ModuleView m) {
        moduleService.setEnabled(m.getModuleId(), m.isEnabled());
        addMessage(FacesMessage.SEVERITY_INFO,
                "Modul '" + m.getDisplayName() + "' " + (m.isEnabled() ? "aktiviert" : "deaktiviert") + ".");
    }

    /** Exportiert die Daten eines Moduls als JSON-Download ({@code p:fileDownload}, nicht-ajax). */
    public void export(ModuleView m) {
        try {
            String json = moduleDataService.export(m.getModuleId());
            String zeitstempel = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String dateiname = "modul_" + m.getModuleId() + "_" + zeitstempel + ".json";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exportFile = DefaultStreamedContent.builder()
                    .name(dateiname)
                    .contentType("application/json")
                    .contentLength((long) bytes.length)
                    .stream(() -> new ByteArrayInputStream(bytes))
                    .build();
        } catch (Exception e) {
            log.error("Export von Modul '{}' fehlgeschlagen", m.getModuleId(), e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Export fehlgeschlagen: " + e.getMessage());
        }
    }

    /** Öffnet den Import-Dialog für ein Modul. */
    public void importVorbereiten(ModuleView m) {
        importModuleId = m.getModuleId();
        importModuleDisplayName = m.getDisplayName();
        importBytes = null;
        importFileName = null;
    }

    /** Übernimmt eine hochgeladene Modul-Export-Datei (noch nicht importiert). */
    public void handleImportUpload(FileUploadEvent event) {
        UploadedFile file = event.getFile();
        if (file == null || file.getContent() == null || file.getContent().length == 0) {
            return;
        }
        importBytes = file.getContent();
        importFileName = file.getFileName();
    }

    /** Importiert die hochgeladene Datei ins Modul {@link #importModuleId}. */
    public void importUebernehmen() {
        if (importBytes == null) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Bitte zuerst eine Export-Datei hochladen.");
            return;
        }
        try {
            ModuleDataService.ImportResult result = moduleDataService.importData(importModuleId, importBytes);
            if (result.fehler().isEmpty()) {
                addMessage(FacesMessage.SEVERITY_INFO,
                        result.gespeichert() + " von " + result.gesamt() + " Einträgen importiert.");
            } else {
                addMessage(FacesMessage.SEVERITY_WARN,
                        result.gespeichert() + " von " + result.gesamt() + " Einträgen importiert, Fehler: "
                                + String.join("; ", result.fehler()));
            }
        } catch (Exception e) {
            log.error("Import in Modul '{}' fehlgeschlagen", importModuleId, e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Import fehlgeschlagen: " + e.getMessage());
        } finally {
            importBytes = null;
            importFileName = null;
        }
    }

    /** Öffnet den Danger-Zone-Dialog für ein Modul. */
    public void clearVorbereiten(ModuleView m) {
        clearModuleId = m.getModuleId();
        clearModuleDisplayName = m.getDisplayName();
        clearBestaetigung = null;
    }

    /** Client-seitige Vorprüfung für den „Daten leeren"-Button (serverseitig re-validiert). */
    public boolean isClearBestaetigungOk() {
        return clearModuleDisplayName != null && clearModuleDisplayName.equals(clearBestaetigung);
    }

    /**
     * Leert die Daten des Moduls {@link #clearModuleId} — Backup wird vorher automatisch erzeugt
     * und (bei Erfolg) als Download angeboten ({@code p:fileDownload} auf {@link #exportFile}).
     */
    public void clearData() {
        try {
            ModuleDangerZoneService.ClearResult result = dangerZoneService.clearData(clearModuleId, clearBestaetigung);
            byte[] bytes = result.exportJson().getBytes(StandardCharsets.UTF_8);
            String zeitstempel = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            exportFile = DefaultStreamedContent.builder()
                    .name("modul_" + clearModuleId + "_backup-vor-leeren_" + zeitstempel + ".json")
                    .contentType("application/json")
                    .contentLength((long) bytes.length)
                    .stream(() -> new ByteArrayInputStream(bytes))
                    .build();
            addMessage(FacesMessage.SEVERITY_WARN,
                    "Daten von Modul '" + clearModuleDisplayName + "' geleert: " + result.geloeschtProEntity()
                            + " — Backup wurde vor dem Leeren automatisch heruntergeladen.");
        } catch (Exception e) {
            log.error("Daten leeren von Modul '{}' fehlgeschlagen", clearModuleId, e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Leeren fehlgeschlagen: " + e.getMessage());
        } finally {
            clearBestaetigung = null;
        }
    }

    private void addMessage(FacesMessage.Severity severity, String text) {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            ctx.addMessage(null, new FacesMessage(severity, text, null));
        }
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.anforderungen.entity.Anforderung;
import ch.plaintext.anforderungen.entity.Howto;
import ch.plaintext.anforderungen.repository.HowtoRepository;
import ch.plaintext.anforderungen.service.AnforderungService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

@Component
@Scope("session")
@Getter
@Setter
@Slf4j
public class AnforderungenBackingBean implements Serializable {

    private final AnforderungService service;
    private final PlaintextSecurity security;
    private final HowtoRepository howtoRepository;

    private List<Anforderung> anforderungen;
    private List<Anforderung> tableFilteredAnforderungen;
    private Anforderung selected;
    private String filterStatus;
    private boolean admin;
    private List<Howto> availableHowtos = new java.util.ArrayList<>();
    private List<Long> selectedHowtoIds = new java.util.ArrayList<>();

    public AnforderungenBackingBean(AnforderungService service, PlaintextSecurity security, HowtoRepository howtoRepository) {
        this.service = service;
        this.security = security;
        this.howtoRepository = howtoRepository;
    }

    /**
     * preRenderView listener (session-scoped): sets the role, locks out non-admins via redirect and
     * loads the data FRESH on every GET. The isPostback guard prevents a reload on Ajax postbacks.
     * Replaces the former @PostConstruct init() + checkAccess().
     */
    public void onLoad() {
        admin = security.ifGranted("ROLE_ADMIN") || security.ifGranted("ROLE_ROOT");
        if (!admin) {
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
        filterStatus = "ALLE";
        loadData();
        loadHowtos();
    }

    private void loadHowtos() {
        try {
            availableHowtos = howtoRepository.findByActiveTrue();
        } catch (Exception e) {
            log.error("Error loading howtos", e);
            availableHowtos = List.of();
        }
    }

    private void loadData() {
        try {
            anforderungen = service.getAllAnforderungenForCurrentUser();
        } catch (Exception e) {
            log.error("Error loading anforderungen", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Daten konnten nicht geladen werden");
        }
    }

    public void select() {
        // Selected in UI
        // Load selected howto IDs
        if (selected != null && selected.getHowtoIds() != null && !selected.getHowtoIds().trim().isEmpty()) {
            selectedHowtoIds = java.util.Arrays.stream(selected.getHowtoIds().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::valueOf)
                    .collect(java.util.stream.Collectors.toList());
        } else {
            selectedHowtoIds = new java.util.ArrayList<>();
        }
    }

    public void clearSelection() {
        selected = null;
        selectedHowtoIds = new java.util.ArrayList<>();
    }

    public void newAnforderung() {
        selected = new Anforderung();
        selected.setMandat(security.getMandat());
        selected.setStatus("OFFEN");
        selected.setPriority("MITTEL");
        selectedHowtoIds = new java.util.ArrayList<>();
    }

    public void save() {
        if (selected == null) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Anforderung ausgewählt");
            return;
        }

        if (selected.getTitel() == null || selected.getTitel().trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Titel ist erforderlich");
            return;
        }

        try {
            // Save howto IDs as comma-separated string
            if (selectedHowtoIds != null && !selectedHowtoIds.isEmpty()) {
                selected.setHowtoIds(selectedHowtoIds.stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(",")));
            } else {
                selected.setHowtoIds(null);
            }

            Anforderung saved = service.save(selected);
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Anforderung gespeichert");
            Long savedId = saved.getId();
            loadData();

            // Restore selection after reload
            if (savedId != null) {
                selected = anforderungen.stream()
                        .filter(a -> savedId.equals(a.getId()))
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception e) {
            log.error("Error saving anforderung", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Speichern fehlgeschlagen");
        }
    }

    public void delete() {
        if (selected == null) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Anforderung ausgewählt");
            return;
        }

        try {
            service.delete(selected.getId());
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Anforderung gelöscht");
            selected = null;
            loadData();
        } catch (Exception e) {
            log.error("Error deleting anforderung", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Löschen fehlgeschlagen");
        }
    }

    public List<Anforderung> getFilteredAnforderungen() {
        if (filterStatus == null || filterStatus.isEmpty() || "ALLE".equals(filterStatus)) {
            return anforderungen;
        }
        return anforderungen.stream()
                .filter(a -> filterStatus.equals(a.getStatus()))
                .toList();
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesMessages.meldung(severity, summary, detail);
    }

    public List<String> getStatusList() {
        return List.of("OFFEN", "IN_BEARBEITUNG", "FEEDBACK", "ERLEDIGT", "ABGELEHNT");
    }

    public List<String> getPriorityList() {
        return List.of("NIEDRIG", "MITTEL", "HOCH", "KRITISCH");
    }

    public long getOffeneCount() {
        return service.countByStatus(security.getMandat(), "OFFEN");
    }

    public long getInBearbeitungCount() {
        return service.countByStatus(security.getMandat(), "IN_BEARBEITUNG");
    }

    public long getErledigtCount() {
        return service.countByStatus(security.getMandat(), "ERLEDIGT");
    }
}

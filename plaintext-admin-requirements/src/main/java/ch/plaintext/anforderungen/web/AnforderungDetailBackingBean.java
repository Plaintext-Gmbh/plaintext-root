/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.anforderungen.entity.Anforderung;
import ch.plaintext.anforderungen.entity.ClaudePrompt;
import ch.plaintext.anforderungen.entity.Howto;
import ch.plaintext.anforderungen.repository.ClaudePromptRepository;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Backing Bean for mobile-friendly Anforderung detail page
 */
@Component
@Scope("session")
@Slf4j
public class AnforderungDetailBackingBean implements Serializable {

    private final AnforderungService anforderungService;
    private final HowtoRepository howtoRepository;
    private final ClaudePromptRepository claudePromptRepository;
    private final PlaintextSecurity security;

    @Getter @Setter
    private Long anforderungId;

    @Getter @Setter
    private Anforderung anforderung;

    // Edit mode flags
    @Getter @Setter
    private boolean editingTitle = false;

    @Getter @Setter
    private boolean editingPriority = false;

    @Getter @Setter
    private boolean editingDescription = false;

    @Getter @Setter
    private boolean editingWiederkehrend = false;

    @Getter @Setter
    private boolean editingStatus = false;

    // Temporary edit values
    @Getter @Setter
    private String tempTitle;

    @Getter @Setter
    private String tempPriority;

    @Getter @Setter
    private String tempDescription;

    @Getter @Setter
    private Boolean tempWiederkehrend;

    @Getter @Setter
    private String tempStatus;

    // Howto selection
    @Getter @Setter
    private boolean editingHowtos = false;

    @Getter @Setter
    private Set<Long> selectedHowtoIds = new HashSet<>();

    @Getter
    private List<Howto> availableHowtos = new ArrayList<>();

    @Getter @Setter
    private boolean isNewMode = false;

    public AnforderungDetailBackingBean(AnforderungService anforderungService, HowtoRepository howtoRepository,
                                        ClaudePromptRepository claudePromptRepository, PlaintextSecurity security) {
        this.anforderungService = anforderungService;
        this.howtoRepository = howtoRepository;
        this.claudePromptRepository = claudePromptRepository;
        this.security = security;
    }

    /**
     * preRenderView-Listener (session-scoped): laedt die Anforderung anhand des viewParam {@code id} bei jedem GET
     * (bzw. legt im Neu-Modus eine an). viewParams sind vor preRenderView gesetzt, daher ist {@code anforderungId}
     * hier verfuegbar. Der isPostback-Guard verhindert das Neuladen bei Ajax-Postbacks (Bearbeitungszustand bleibt
     * erhalten, keine doppelte Neuanlage).
     */
    public void onLoad() {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        loadAnforderung();
        loadAvailableHowtos();
    }

    private void loadAnforderung() {
        if (anforderungId != null) {
            try {
                anforderung = anforderungService.findById(anforderungId).orElse(null);
                if (anforderung == null) {
                    log.warn("Anforderung not found: {}", anforderungId);
                } else {
                    log.info("Loaded anforderung {} for detail view", anforderungId);
                    loadSelectedHowtos();
                    isNewMode = false;
                }
            } catch (Exception e) {
                log.error("Error loading anforderung: {}", anforderungId, e);
            }
        } else {
            // Neu-Modus: Erstelle neue Anforderung mit Standardwerten
            log.info("Creating new anforderung in mobile view");
            createNewAnforderung();
        }
    }

    private void createNewAnforderung() {
        isNewMode = true;
        anforderung = new Anforderung();
        anforderung.setMandat(security.getMandat());
        anforderung.setErsteller(security.getUser());

        // Standardwerte gemäss Anforderung
        anforderung.setTitel("-");
        anforderung.setBeschreibung("-");
        anforderung.setStatus("OFFEN");
        anforderung.setPriority("MITTEL");
        anforderung.setWiederkehrend(false);
        anforderung.setKategorie("-");

        // Sofort speichern, damit die ID verfügbar ist
        try {
            anforderung = anforderungService.save(anforderung);
            anforderungId = anforderung.getId();
            log.info("Created new anforderung with ID {} and default values", anforderungId);

            // Update URL to include ID (prevents duplicate saves on refresh)
            FacesContext.getCurrentInstance().getExternalContext()
                .redirect("anforderungdetail.xhtml?id=" + anforderungId);
        } catch (Exception e) {
            log.error("Error creating new anforderung", e);
            addMessage("Fehler beim Erstellen: " + e.getMessage(), FacesMessage.SEVERITY_ERROR);
        }
    }

    private void loadAvailableHowtos() {
        try {
            availableHowtos = howtoRepository.findByActiveTrue();
            log.info("Loaded {} active howtos", availableHowtos.size());
        } catch (Exception e) {
            log.error("Error loading howtos", e);
            availableHowtos = new ArrayList<>();
        }
    }

    private void loadSelectedHowtos() {
        selectedHowtoIds.clear();
        if (anforderung != null && anforderung.getHowtoIds() != null && !anforderung.getHowtoIds().isEmpty()) {
            try {
                String[] ids = anforderung.getHowtoIds().split(",");
                for (String id : ids) {
                    selectedHowtoIds.add(Long.parseLong(id.trim()));
                }
            } catch (Exception e) {
                log.error("Error parsing howto IDs: {}", anforderung.getHowtoIds(), e);
            }
        }
    }

    // Edit Title
    public void startEditTitle() {
        tempTitle = anforderung.getTitel();
        editingTitle = true;
    }

    public void saveTitle() {
        try {
            anforderung.setTitel(tempTitle);
            anforderungService.save(anforderung);
            editingTitle = false;
            addMessage("Titel erfolgreich gespeichert", FacesMessage.SEVERITY_INFO);
        } catch (Exception e) {
            log.error("Error saving title", e);
            addMessage("Fehler beim Speichern: " + e.getMessage(), FacesMessage.SEVERITY_ERROR);
        }
    }

    public void cancelEditTitle() {
        editingTitle = false;
        tempTitle = null;
    }

    // Edit Priority
    public void startEditPriority() {
        tempPriority = anforderung.getPriority();
        editingPriority = true;
    }

    public void savePriority() {
        try {
            anforderung.setPriority(tempPriority);
            anforderungService.save(anforderung);
            editingPriority = false;
            addMessage("Priorität erfolgreich gespeichert", FacesMessage.SEVERITY_INFO);
        } catch (Exception e) {
            log.error("Error saving priority", e);
            addMessage("Fehler beim Speichern: " + e.getMessage(), FacesMessage.SEVERITY_ERROR);
        }
    }

    public void cancelEditPriority() {
        editingPriority = false;
        tempPriority = null;
    }

    // Edit Description
    public void startEditDescription() {
        tempDescription = anforderung.getBeschreibung();
        editingDescription = true;
    }

    public void saveDescription() {
        try {
            anforderung.setBeschreibung(tempDescription);
            anforderungService.save(anforderung);
            editingDescription = false;
            addMessage("Beschreibung erfolgreich gespeichert", FacesMessage.SEVERITY_INFO);
        } catch (Exception e) {
            log.error("Error saving description", e);
            addMessage("Fehler beim Speichern: " + e.getMessage(), FacesMessage.SEVERITY_ERROR);
        }
    }

    public void cancelEditDescription() {
        editingDescription = false;
        tempDescription = null;
    }

    // Edit Wiederkehrend
    public void startEditWiederkehrend() {
        tempWiederkehrend = anforderung.getWiederkehrend();
        editingWiederkehrend = true;
    }

    public void saveWiederkehrend() {
        try {
            anforderung.setWiederkehrend(tempWiederkehrend);
            anforderungService.save(anforderung);
            editingWiederkehrend = false;
            addMessage("Wiederkehrend erfolgreich gespeichert", FacesMessage.SEVERITY_INFO);
        } catch (Exception e) {
            log.error("Error saving wiederkehrend", e);
            addMessage("Fehler beim Speichern: " + e.getMessage(), FacesMessage.SEVERITY_ERROR);
        }
    }

    public void cancelEditWiederkehrend() {
        editingWiederkehrend = false;
        tempWiederkehrend = null;
    }

    // Edit Status
    public void startEditStatus() {
        tempStatus = anforderung.getStatus();
        editingStatus = true;
    }

    public void saveStatus() {
        try {
            anforderung.setStatus(tempStatus);
            anforderungService.save(anforderung);
            editingStatus = false;
            addMessage("Status erfolgreich gespeichert", FacesMessage.SEVERITY_INFO);
        } catch (Exception e) {
            log.error("Error saving status", e);
            addMessage("Fehler beim Speichern: " + e.getMessage(), FacesMessage.SEVERITY_ERROR);
        }
    }

    public void cancelEditStatus() {
        editingStatus = false;
        tempStatus = null;
    }

    // Edit Howtos
    public void startEditHowtos() {
        loadSelectedHowtos();
        editingHowtos = true;
    }

    public void saveHowtos() {
        try {
            // Convert selected IDs to comma-separated string
            String howtoIdsString = selectedHowtoIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));

            anforderung.setHowtoIds(howtoIdsString.isEmpty() ? null : howtoIdsString);
            anforderungService.save(anforderung);
            editingHowtos = false;
            addMessage("Howtos erfolgreich gespeichert", FacesMessage.SEVERITY_INFO);
        } catch (Exception e) {
            log.error("Error saving howtos", e);
            addMessage("Fehler beim Speichern: " + e.getMessage(), FacesMessage.SEVERITY_ERROR);
        }
    }

    public void cancelEditHowtos() {
        editingHowtos = false;
        loadSelectedHowtos();
    }

    // Helper method to check if a howto is selected
    public boolean isHowtoSelected(Long howtoId) {
        return selectedHowtoIds.contains(howtoId);
    }

    // Toggle howto selection and save immediately
    public void toggleHowto(Long howtoId) {
        try {
            if (selectedHowtoIds.contains(howtoId)) {
                selectedHowtoIds.remove(howtoId);
                log.info("Removed howto {} from selection", howtoId);
            } else {
                selectedHowtoIds.add(howtoId);
                log.info("Added howto {} to selection", howtoId);
            }

            // Save immediately to database
            String howtoIdsString = selectedHowtoIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));

            anforderung.setHowtoIds(howtoIdsString.isEmpty() ? null : howtoIdsString);
            anforderungService.save(anforderung);
            log.info("Saved howto selection: {}", howtoIdsString);

            // Reload to ensure consistency
            loadSelectedHowtos();
        } catch (Exception e) {
            log.error("Error toggling howto", e);
            addMessage("Fehler beim Ändern der Auswahl: " + e.getMessage(), FacesMessage.SEVERITY_ERROR);
            // Reload to revert changes
            loadSelectedHowtos();
        }
    }

    // Get selected Howtos for display
    public List<Howto> getSelectedHowtos() {
        if (selectedHowtoIds.isEmpty()) {
            return Collections.emptyList();
        }
        return availableHowtos.stream()
                .filter(h -> selectedHowtoIds.contains(h.getId()))
                .collect(Collectors.toList());
    }

    // Get all priority values for dropdown
    public List<String> getPriorityValues() {
        return Arrays.asList("NIEDRIG", "MITTEL", "HOCH", "KRITISCH");
    }

    // Get all status values for dropdown
    public List<String> getStatusValues() {
        return Arrays.asList("OFFEN", "IN_BEARBEITUNG", "FEEDBACK", "ERLEDIGT", "ABGELEHNT");
    }

    private void addMessage(String message, FacesMessage.Severity severity) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, message, null));
    }

    /**
     * Get the last Claude question (prompt text) if status is FEEDBACK
     * Returns the most recent FEEDBACK_QUESTION ClaudePrompt's promptText for this anforderung
     */
    public String getLastClaudeQuestion() {
        if (anforderung == null || !"FEEDBACK".equals(anforderung.getStatus())) {
            return null;
        }

        try {
            List<ClaudePrompt> prompts = claudePromptRepository.findByAnforderungIdOrderByCreatedDateDesc(anforderung.getId());
            // Filter for FEEDBACK_QUESTION status only
            for (ClaudePrompt prompt : prompts) {
                if ("FEEDBACK_QUESTION".equals(prompt.getStatus())) {
                    return prompt.getPromptText();
                }
            }
        } catch (Exception e) {
            log.error("Error loading last Claude question for anforderung {}", anforderung.getId(), e);
        }

        return null;
    }

    /**
     * Check if there is a Claude question to display
     */
    public boolean hasClaudeQuestion() {
        return getLastClaudeQuestion() != null;
    }
}

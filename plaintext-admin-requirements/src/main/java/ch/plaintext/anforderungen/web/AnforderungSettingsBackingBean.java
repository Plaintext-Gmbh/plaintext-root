/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.anforderungen.entity.AnforderungApiSettings;
import ch.plaintext.anforderungen.entity.ClaudePrompt;
import ch.plaintext.anforderungen.repository.AnforderungApiSettingsRepository;
import ch.plaintext.anforderungen.service.ClaudeAutomationService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * Backing Bean for anforderungssettings.xhtml
 * Manages global API settings for Claude automation
 */
@Component("anforderungSettingsBackingBean")
@Scope("session")
@Data
@Slf4j
public class AnforderungSettingsBackingBean implements Serializable {

    @Autowired
    private AnforderungApiSettingsRepository apiSettingsRepository;

    @Autowired
    private ClaudeAutomationService claudeAutomationService;

    @Autowired
    private PlaintextSecurity security;

    private Long settingsId;
    private String apiToken;
    private Boolean claudeAutomationEnabled = false;

    private List<ClaudePrompt> promptHistory;

    /**
     * preRenderView-Listener (session-scoped): laedt/initialisiert die API-Settings des Mandanten bei jedem GET.
     * Der isPostback-Guard verhindert das Neuladen bei Ajax-Postbacks. Ersetzt das fruehere @PostConstruct init().
     */
    public void onLoad() {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        try {
            String mandat = getCurrentMandat();
            // Load or create settings for current mandat
            Optional<AnforderungApiSettings> settingsOpt = apiSettingsRepository.findByMandat(mandat);

            if (settingsOpt.isEmpty()) {
                // Create default settings for this mandat
                AnforderungApiSettings settings = new AnforderungApiSettings();
                settings.setMandat(mandat);
                settings.setClaudeAutomationEnabled(false);
                settings = apiSettingsRepository.save(settings);
                loadSettings(settings.getId());
            } else {
                loadSettings(settingsOpt.get().getId());
            }
        } catch (Exception e) {
            log.error("Error initializing AnforderungSettingsBackingBean", e);
            // Set safe defaults
            this.claudeAutomationEnabled = false;
            this.promptHistory = List.of();
        }
    }

    public void loadSettings(Long id) {
        try {
            apiSettingsRepository.findById(id).ifPresent(settings -> {
                this.settingsId = settings.getId();
                this.apiToken = settings.getApiToken();
                this.claudeAutomationEnabled = settings.getClaudeAutomationEnabled();
            });

            // Load prompt history (all prompts for current mandat)
            this.promptHistory = claudeAutomationService.getAllPrompts();
        } catch (Exception e) {
            log.error("Error loading settings", e);
            // Set defaults
            this.claudeAutomationEnabled = false;
            this.promptHistory = List.of();
        }
    }

    public void generateToken() {
        this.apiToken = claudeAutomationService.generateApiToken();
        addMessage("Token Generated", "New API token generated. Please save settings.");
    }

    public void save() {
        try {
            String mandat = getCurrentMandat();
            // Get or create settings for current mandat
            Optional<AnforderungApiSettings> settingsOpt = apiSettingsRepository.findByMandat(mandat);
            AnforderungApiSettings settings;

            if (settingsOpt.isEmpty()) {
                // Create new settings
                settings = new AnforderungApiSettings();
                settings.setMandat(mandat);
                log.info("Creating new API settings for mandat: {}", mandat);
            } else {
                // Use existing settings
                settings = settingsOpt.get();
                log.info("Updating existing API settings for mandat: {} with ID: {}", mandat, settings.getId());
            }

            // Update fields
            settings.setApiToken(this.apiToken);
            settings.setClaudeAutomationEnabled(this.claudeAutomationEnabled);

            // Save
            settings = apiSettingsRepository.save(settings);
            this.settingsId = settings.getId();

            addMessage("Success", "API settings saved successfully for mandat: " + mandat);
            log.info("Claude automation settings saved: id={}, mandat={}, enabled={}, hasToken={}",
                     settings.getId(), mandat, this.claudeAutomationEnabled, this.apiToken != null);
        } catch (Exception e) {
            log.error("Error saving settings", e);
            addMessage("Error", "Failed to save settings: " + e.getMessage());
        }
    }

    private String getCurrentMandat() {
        String mandat = security.getMandat();
        if (mandat == null || "NO_AUTH".equals(mandat) || "NO_USER".equals(mandat) || "ERROR".equals(mandat)) {
            throw new IllegalStateException("Cannot access settings - invalid mandat: " + mandat);
        }
        return mandat;
    }

    private void addMessage(String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, summary, detail));
    }
}

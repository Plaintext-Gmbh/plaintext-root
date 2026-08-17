/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.menu.MenuAnnotation;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.service.MandateMenuVisibilityService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Backing bean for mandate menu configuration page.
 *
 * @author plaintext.ch
 * @since 1.39.0
 */
@Slf4j
@Scope("session")
@Component
@Data
@MenuAnnotation(
    icon = "pi pi-list",
    title = "Menüsteuerung",
    parent = "Root",
    link = "mandatemenu.html",
    order = 65,
    roles = {"ROOT"}
)
public class MandateMenuBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    private transient MandateMenuVisibilityService service;

    @Autowired
    private PlaintextSecurity plaintextSecurity;

    private List<MandateMenuConfig> mandates = new ArrayList<>();
    private MandateMenuConfig selected;
    private List<String> availableMenus = new ArrayList<>();

    @PostConstruct
    public void init() {
        loadMandates();
    }

    /**
     * Load all mandates and their configurations.
     */
    private void loadMandates() {
        mandates.clear();

        try {
            // Get all known mandates
            Set<String> mandateNames = getAllMandateNames();

            for (String mandateName : mandateNames) {
                try {
                    MandateMenuConfig config = service.getOrCreateConfig(mandateName);
                    mandates.add(config);
                } catch (Exception e) {
                    log.error("Error loading config for mandate '{}': {}", mandateName, e.getMessage());
                }
            }

            if (!mandates.isEmpty()) {
                selected = mandates.get(0);
            }

            // Load available menus
            availableMenus = service.getAllMenuTitles();
            log.debug("Loaded {} mandates and {} menu items", mandates.size(), availableMenus.size());
        } catch (Exception e) {
            log.error("Error loading mandates: {}", e.getMessage(), e);
            // Ensure we have at least a default mandate
            if (mandates.isEmpty()) {
                try {
                    MandateMenuConfig defaultConfig = service.getOrCreateConfig("default");
                    mandates.add(defaultConfig);
                    selected = defaultConfig;
                } catch (Exception ex) {
                    log.error("Could not create default mandate config", ex);
                }
            }
        }
    }

    /**
     * Get all known mandate names from the security system.
     */
    private Set<String> getAllMandateNames() {
        Set<String> mandates = new HashSet<>();

        try {
            // Get all mandates from the security system
            if (plaintextSecurity != null) {
                Set<String> allMandate = plaintextSecurity.getAllMandate();
                if (allMandate != null && !allMandate.isEmpty()) {
                    mandates.addAll(allMandate);
                }
            }
        } catch (Exception e) {
            log.warn("Could not load mandates from security system: {}", e.getMessage());
        }

        // Always include default if not already present
        mandates.add("default");

        return mandates;
    }

    /**
     * Initialize the detail page - called via preRenderView.
     */
    public void initDetail() {
        log.debug("Initializing detail page for mandate: {}", selected != null ? selected.getMandateName() : "new");

        // Always reload menus to ensure fresh data
        availableMenus = service.getAllMenuTitles();

        // Ensure we have a selected mandate
        if (selected == null) {
            selected = new MandateMenuConfig();
        }

        // Copy hiddenMenus to a new HashSet to avoid Hibernate lazy initialization issues
        if (selected.getHiddenMenus() != null) {
            selected.setHiddenMenus(new HashSet<>(selected.getHiddenMenus()));
        }
    }

    /**
     * Called when a mandate is selected in the UI.
     */
    public void selectMandate() {
        log.debug("Selected mandate: {}", selected != null ? selected.getMandateName() : "null");
    }

    /**
     * Create a new mandate configuration.
     */
    public void newMandate() {
        selected = new MandateMenuConfig();
        try {
            FacesContext.getCurrentInstance().getExternalContext().redirect("mandatemenudetail.xhtml");
        } catch (Exception e) {
            log.error("Error redirecting to detail page", e);
        }
    }

    /**
     * Edit the selected mandate.
     */
    public void edit() {
        log.debug("Edit called for mandate: {}", selected != null ? selected.getMandateName() : "null");
    }

    /**
     * Save the selected mandate configuration.
     */
    public void save() {
        FacesContext context = FacesContext.getCurrentInstance();

        if (selected == null) {
            context.addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Keine Konfiguration ausgewählt."));
            return;
        }

        if (selected.getMandateName() == null || selected.getMandateName().trim().isEmpty()) {
            context.addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Mandatsname darf nicht leer sein."));
            return;
        }

        try {
            // Save with transactional service method that handles the collection properly
            service.saveConfig(selected.getMandateName(), selected.getHiddenMenus(), Boolean.TRUE.equals(selected.getWhitelistMode()));
            context.addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Menükonfiguration gespeichert."));

            // Redirect back to overview
            FacesContext.getCurrentInstance().getExternalContext().redirect("mandatemenu.xhtml");
        } catch (Exception e) {
            log.error("Error saving mandate menu configuration", e);
            context.addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Fehler beim Speichern: " + e.getMessage()));
        }
    }

    /**
     * Delete the selected mandate configuration.
     */
    public void delete() {
        FacesContext context = FacesContext.getCurrentInstance();

        if (selected == null) {
            context.addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Keine Konfiguration ausgewählt."));
            return;
        }

        try {
            service.deleteConfig(selected);
            context.addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Menükonfiguration gelöscht."));

            selected = null;
            loadMandates();

            // Redirect back to overview
            FacesContext.getCurrentInstance().getExternalContext().redirect("mandatemenu.xhtml");
        } catch (Exception e) {
            log.error("Error deleting mandate menu configuration", e);
            context.addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Fehler beim Löschen: " + e.getMessage()));
        }
    }

    /**
     * Get all available mandates from the security system as a sorted list.
     * @return List of all mandate names
     */
    public List<String> getAllMandate() {
        List<String> mandateList = new ArrayList<>(getAllMandateNames());
        mandateList.sort(String::compareTo);
        return mandateList;
    }

    public void selectAll() {
        if (selected != null) {
            selected.setHiddenMenus(new HashSet<>(availableMenus));
        }
    }

    public void deselectAll() {
        if (selected != null) {
            selected.setHiddenMenus(new HashSet<>());
        }
    }

    public void invertSelection() {
        if (selected != null) {
            Set<String> allMenus = new HashSet<>(availableMenus);
            Set<String> current = selected.getHiddenMenus() != null ? selected.getHiddenMenus() : new HashSet<>();
            Set<String> inverted = new HashSet<>();
            for (String menu : allMenus) {
                if (!current.contains(menu)) {
                    inverted.add(menu);
                }
            }
            selected.setHiddenMenus(inverted);
        }
    }

    /**
     * Toggle between whitelist and blacklist mode for the selected mandate.
     * This will invert the current selection.
     */
    public void toggleMode() {
        if (selected == null) {
            log.warn("Cannot toggle mode: no mandate selected");
            return;
        }

        try {
            // Get all available menus
            Set<String> allMenus = new HashSet<>(availableMenus);

            // Create inverted selection: all menus that are NOT currently in hiddenMenus
            Set<String> invertedSelection = new HashSet<>();
            for (String menu : allMenus) {
                if (!selected.getHiddenMenus().contains(menu)) {
                    invertedSelection.add(menu);
                }
            }

            // Update the selection and toggle the mode
            selected.setHiddenMenus(invertedSelection);
            selected.setWhitelistMode(!Boolean.TRUE.equals(selected.getWhitelistMode()));

            log.debug("Toggled mode for mandate '{}' to {} mode. New selection size: {}",
                selected.getMandateName(),
                Boolean.TRUE.equals(selected.getWhitelistMode()) ? "whitelist" : "blacklist",
                selected.getHiddenMenus().size());

        } catch (Exception e) {
            log.error("Error toggling mode", e);
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                    "Fehler beim Umschalten des Modus: " + e.getMessage()));
        }
    }
}

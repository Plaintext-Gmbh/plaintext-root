/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.menu.MenuAnnotation;
import ch.plaintext.boot.menu.ModuleRoleService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Backing bean for mandate menu configuration page.
 *
 * <p><b>Two forms of list entries</b> (since 1.608.0, see {@link MandateMenuConfig}): a
 * {@code modul:<moduleId>} entry switches a whole module, a menu title exactly one item. The detail
 * page keeps three separate selections — modules, menu items, and the entries that point nowhere in
 * the current menu tree — and merges them back into a single list when saving. The third group is
 * the reason the migration loses nothing: existing entries without a match stay visible and
 * selected instead of silently disappearing on the next save.</p>
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

    /**
     * Supplies the detected module keys — the valid choices for {@code modul:} entries.
     * Optional: without the service the module selection stays empty, while the title selection
     * works unchanged.
     */
    @Autowired(required = false)
    private transient ModuleRoleService moduleRoleService;

    private List<MandateMenuConfig> mandates = new ArrayList<>();
    private MandateMenuConfig selected;
    private List<String> availableMenus = new ArrayList<>();

    /** Selectable module entries, in the form {@code modul:<key>}. */
    private List<String> availableModuleEntries = new ArrayList<>();

    /** Module entries selected on the detail page. */
    private List<String> selectedModuleEntries = new ArrayList<>();

    /** Menu titles selected on the detail page. */
    private List<String> selectedTitleEntries = new ArrayList<>();

    /** Stored entries that point nowhere in the current menu tree. */
    private List<String> deadEntries = new ArrayList<>();

    /** Those of them that are kept (preselected — nothing is lost unintentionally). */
    private List<String> selectedDeadEntries = new ArrayList<>();

    /**
     * Whether the detail page has split the stored list into the three selections. Only then may
     * {@link #save()} reassemble it from those selections.
     */
    private boolean detailBearbeitung;

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
            availableModuleEntries = ladeModulEintraege();
            log.debug("Loaded {} mandates, {} menu items and {} module entries",
                    mandates.size(), availableMenus.size(), availableModuleEntries.size());
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
        availableModuleEntries = ladeModulEintraege();

        // Ensure we have a selected mandate
        if (selected == null) {
            selected = new MandateMenuConfig();
        }

        // Copy hiddenMenus to a new HashSet to avoid Hibernate lazy initialization issues
        if (selected.getHiddenMenus() != null) {
            selected.setHiddenMenus(new HashSet<>(selected.getHiddenMenus()));
        }

        teileAuswahlAuf();
    }

    /**
     * The selectable module entries ({@code modul:<key>}) built from the detected module keys.
     *
     * @return alphabetically sorted module entries (never {@code null})
     */
    private List<String> ladeModulEintraege() {
        List<String> ret = new ArrayList<>();
        if (moduleRoleService == null) {
            return ret;
        }
        try {
            for (String key : moduleRoleService.getKnownModuleKeys()) {
                String eintrag = MandateMenuConfig.moduleEntryOf(key);
                if (!eintrag.isEmpty()) {
                    ret.add(eintrag);
                }
            }
        } catch (Exception e) {
            log.warn("Modul-Keys nicht ermittelbar: {}", e.getMessage());
        }
        return ret;
    }

    /**
     * Splits the stored list into the three selections of the detail page: module entries, menu
     * titles, and entries without a match in the current menu tree.
     */
    private void teileAuswahlAuf() {
        selectedModuleEntries = new ArrayList<>();
        selectedTitleEntries = new ArrayList<>();
        deadEntries = new ArrayList<>();

        Set<String> bekannteModule = new HashSet<>(availableModuleEntries);
        Set<String> bekannteTitel = new HashSet<>(availableMenus);

        Set<String> gespeichert = selected.getHiddenMenus() == null ? Set.of() : selected.getHiddenMenus();
        for (String eintrag : new TreeSet<>(gespeichert)) {
            if (eintrag == null || eintrag.isBlank()) {
                continue;
            }
            String normalisiert = normalisiereModulEintrag(eintrag);
            if (bekannteModule.contains(normalisiert)) {
                selectedModuleEntries.add(normalisiert);
            } else if (bekannteTitel.contains(eintrag)) {
                selectedTitleEntries.add(eintrag);
            } else {
                deadEntries.add(eintrag);
            }
        }
        // Dead entries stay preselected: saving must not throw away anything the editor has not
        // deliberately deselected.
        selectedDeadEntries = new ArrayList<>(deadEntries);
        detailBearbeitung = true;
    }

    /**
     * Brings a {@code modul:} entry into its canonical spelling; titles are left untouched.
     *
     * @param eintrag stored list entry
     * @return canonical entry
     */
    private static String normalisiereModulEintrag(String eintrag) {
        String key = MandateMenuConfig.moduleKeyOf(eintrag);
        return key.isEmpty() ? eintrag : MandateMenuConfig.moduleEntryOf(key);
    }

    /**
     * Merges the three selections back into one list — the form in which it is stored.
     *
     * @return the merged list (never {@code null})
     */
    Set<String> zusammengefuehrteAuswahl() {
        Set<String> ret = new LinkedHashSet<>();
        ergaenze(ret, selectedModuleEntries);
        ergaenze(ret, selectedTitleEntries);
        ergaenze(ret, selectedDeadEntries);
        return ret;
    }

    private static void ergaenze(Set<String> ziel, List<String> quelle) {
        if (quelle == null) {
            return;
        }
        for (String eintrag : quelle) {
            if (eintrag != null && !eintrag.isBlank()) {
                ziel.add(eintrag);
            }
        }
    }

    /**
     * Writes the three selections of the detail page back into {@code selected.hiddenMenus}.
     *
     * <p>Only when the detail page has actually split the selection ({@link #teileAuswahlAuf()}).
     * Otherwise a {@code save()} on a selection that was never split — programmatically, say —
     * would replace the stored list with three empty ones.</p>
     */
    private void uebernehmeDetailAuswahl() {
        if (!detailBearbeitung || selected == null) {
            return;
        }
        selected.setHiddenMenus(new HashSet<>(zusammengefuehrteAuswahl()));
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

        uebernehmeDetailAuswahl();

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

    /**
     * All selectable entries: module entries ({@code modul:<key>}) and menu titles. The bulk
     * actions of the detail page operate on this set.
     *
     * @return selectable entries (never {@code null})
     */
    private Set<String> alleWaehlbarenEintraege() {
        Set<String> alle = new HashSet<>(availableModuleEntries);
        alle.addAll(availableMenus);
        return alle;
    }

    public void selectAll() {
        if (selected != null) {
            selected.setHiddenMenus(alleWaehlbarenEintraege());
            teileAuswahlNeuAuf();
        }
    }

    public void deselectAll() {
        if (selected != null) {
            selected.setHiddenMenus(new HashSet<>());
            teileAuswahlNeuAuf();
        }
    }

    public void invertSelection() {
        if (selected != null) {
            Set<String> allMenus = alleWaehlbarenEintraege();
            Set<String> current = selected.getHiddenMenus() != null ? selected.getHiddenMenus() : new HashSet<>();
            Set<String> inverted = new HashSet<>();
            for (String menu : allMenus) {
                if (!current.contains(menu)) {
                    inverted.add(menu);
                }
            }
            selected.setHiddenMenus(inverted);
            teileAuswahlNeuAuf();
        }
    }

    /** Brings the three selections of the detail page up to date after a bulk action. */
    private void teileAuswahlNeuAuf() {
        if (detailBearbeitung) {
            teileAuswahlAuf();
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
            // Get all available entries (modules + menu titles)
            Set<String> allMenus = alleWaehlbarenEintraege();

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
            teileAuswahlNeuAuf();

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

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
 * <p><b>Zwei Formen von Listen-Eintraegen</b> (seit 1.608.0, siehe {@link MandateMenuConfig}): ein
 * {@code modul:<moduleId>}-Eintrag schaltet ein ganzes Modul, ein Menue-Titel genau einen Punkt.
 * Die Detailseite fuehrt drei getrennte Auswahlen — Module, Menuepunkte und die Eintraege, die im
 * aktuellen Menuebaum ins Leere zeigen — und setzt sie beim Speichern wieder zu einer Liste
 * zusammen. Die dritte Gruppe ist der Grund, warum die Umstellung nichts verliert: Bestandseintraege
 * ohne Entsprechung bleiben sichtbar und ausgewaehlt, statt beim naechsten Speichern still
 * wegzufallen.</p>
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
     * Liefert die erkannten Modul-Keys — die gueltige Auswahl fuer {@code modul:}-Eintraege.
     * Optional: ohne den Service bleibt die Modul-Auswahl leer, die Titel-Auswahl funktioniert
     * unveraendert.
     */
    @Autowired(required = false)
    private transient ModuleRoleService moduleRoleService;

    private List<MandateMenuConfig> mandates = new ArrayList<>();
    private MandateMenuConfig selected;
    private List<String> availableMenus = new ArrayList<>();

    /** Waehlbare Modul-Eintraege, Form {@code modul:<key>}. */
    private List<String> availableModuleEntries = new ArrayList<>();

    /** Ausgewaehlte Modul-Eintraege der Detailseite. */
    private List<String> selectedModuleEntries = new ArrayList<>();

    /** Ausgewaehlte Menue-Titel der Detailseite. */
    private List<String> selectedTitleEntries = new ArrayList<>();

    /** Gespeicherte Eintraege, die im aktuellen Menuebaum ins Leere zeigen. */
    private List<String> deadEntries = new ArrayList<>();

    /** Davon behaltene Eintraege (vorausgewaehlt — nichts geht ungewollt verloren). */
    private List<String> selectedDeadEntries = new ArrayList<>();

    /**
     * Ob die Detailseite die gespeicherte Liste in die drei Auswahlen aufgeteilt hat. Nur dann darf
     * {@link #save()} sie aus den Auswahlen wieder zusammensetzen.
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
     * Die waehlbaren Modul-Eintraege ({@code modul:<key>}) aus den erkannten Modul-Keys.
     *
     * @return alphabetisch sortierte Modul-Eintraege (nie {@code null})
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
     * Zerlegt die gespeicherte Liste in die drei Auswahlen der Detailseite: Modul-Eintraege,
     * Menue-Titel und Eintraege ohne Entsprechung im aktuellen Menuebaum.
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
        // Tote Eintraege bleiben vorausgewaehlt: Speichern darf nichts wegwerfen, was der
        // Bearbeiter nicht bewusst abgewaehlt hat.
        selectedDeadEntries = new ArrayList<>(deadEntries);
        detailBearbeitung = true;
    }

    /**
     * Bringt einen {@code modul:}-Eintrag auf die kanonische Schreibweise; Titel bleiben unberuehrt.
     *
     * @param eintrag gespeicherter Listen-Eintrag
     * @return kanonischer Eintrag
     */
    private static String normalisiereModulEintrag(String eintrag) {
        String key = MandateMenuConfig.moduleKeyOf(eintrag);
        return key.isEmpty() ? eintrag : MandateMenuConfig.moduleEntryOf(key);
    }

    /**
     * Setzt die drei Auswahlen wieder zu einer Liste zusammen — die Form, in der sie gespeichert
     * wird.
     *
     * @return die zusammengefuehrte Liste (nie {@code null})
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
     * Schreibt die drei Auswahlen der Detailseite in {@code selected.hiddenMenus} zurueck.
     *
     * <p>Nur, wenn die Detailseite die Auswahl auch aufgeteilt hat ({@link #teileAuswahlAuf()}).
     * Ein {@code save()} auf einer nicht aufgeteilten Auswahl — etwa programmatisch — wuerde sonst
     * die gespeicherte Liste durch drei leere Listen ersetzen.</p>
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
     * Alle waehlbaren Eintraege: Modul-Eintraege ({@code modul:<key>}) und Menue-Titel. Die
     * Sammel-Aktionen der Detailseite arbeiten auf dieser Menge.
     *
     * @return waehlbare Eintraege (nie {@code null})
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

    /** Nach einer Sammel-Aktion die drei Auswahlen der Detailseite nachziehen. */
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
            // Get all available entries (Module + Menue-Titel)
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

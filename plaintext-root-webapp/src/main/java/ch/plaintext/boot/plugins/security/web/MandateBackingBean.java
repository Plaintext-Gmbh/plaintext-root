/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Mandate Backing Bean
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Slf4j
@Scope("session")
@Component
@Named
@Data
public class MandateBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    private PlaintextSecurity plaintextSecurity;

    @Autowired
    private MyUserRepository userRepository;

    @Autowired
    private MandateMenuConfigRepository mandateMenuConfigRepository;

    private List<String> mandate = new ArrayList<>();
    private List<MyUserEntity> users = new ArrayList<>();
    private List<MyUserEntity> filteredUsers; // Für die Tabellen-Filter-Funktion
    private String selectedMandat;
    private String newMandatName;

    @PostConstruct
    public void init() {
        reload();
    }

    public void reload() {
        loadMandate();
        loadAllUsers();
    }

    private void loadMandate() {
        // Behalte manuell erstellte Mandate
        List<String> existingMandate = new ArrayList<>(mandate);

        mandate.clear();
        Set<String> allMandate = plaintextSecurity.getAllMandate();
        mandate.addAll(allMandate);

        // Füge manuell erstellte Mandate hinzu, die noch nicht in der DB sind
        for (String mandat : existingMandate) {
            if (!mandate.contains(mandat)) {
                mandate.add(mandat);
            }
        }

        if (!mandate.isEmpty() && selectedMandat == null) {
            selectedMandat = mandate.get(0);
        }

        log.debug("Loaded {} mandate", mandate.size());
    }

    private void loadAllUsers() {
        users.clear();
        users.addAll(userRepository.findAll());
        log.debug("Loaded {} users", users.size());
    }

    public void selectMandat() {
        log.debug("Selected mandat: {}", selectedMandat);
    }

    public void createMandat() {
        FacesContext context = FacesContext.getCurrentInstance();

        if (newMandatName == null || newMandatName.trim().isEmpty()) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Mandatname darf nicht leer sein."));
            return;
        }

        String mandatKey = newMandatName.trim().toLowerCase();

        if (mandate.contains(mandatKey)) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Mandat existiert bereits."));
            return;
        }

        try {
            // Prüfe ob Mandat bereits in der Datenbank existiert
            if (mandateMenuConfigRepository.existsByMandateName(mandatKey)) {
                context.addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Mandat existiert bereits in der Datenbank."));
                return;
            }

            // Erstelle MandateMenuConfig Entity und speichere in DB
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName(mandatKey);
            mandateMenuConfigRepository.save(config);
            log.info("Saved new mandat '{}' to database", mandatKey);

            newMandatName = "";

            // Lade Mandate neu, um sicherzustellen, dass alle Quellen berücksichtigt werden
            loadMandate();

            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Mandat '" + mandatKey + "' erstellt."));

            log.debug("Created new mandat: {}", mandatKey);

        } catch (Exception e) {
            log.error("Error creating mandat", e);
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                            "Fehler beim Erstellen des Mandats: " + e.getMessage()));
        }
    }

    public void saveUserMandat(MyUserEntity user) {
        try {
            userRepository.save(user);

            // Wenn der aktuell angemeldete Benutzer sein eigenes Mandat ändert, aktualisiere die Sitzung
            if (plaintextSecurity.getId().equals(user.getId())) {
                // Neu laden des aktuellen Benutzers
                loadMandate();
            }

            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Gespeichert",
                            "Mandat für Benutzer " + user.getUsername() + " aktualisiert."));

            log.debug("Updated mandat for user {} to {}", user.getUsername(), user.getMandat());
        } catch (Exception e) {
            log.error("Error saving user mandat", e);
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                            "Fehler beim Speichern: " + e.getMessage()));
        }
    }

    public void deleteMandat() {
        FacesContext context = FacesContext.getCurrentInstance();

        if (selectedMandat == null) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Kein Mandat ausgewählt."));
            return;
        }

        if ("default".equalsIgnoreCase(selectedMandat)) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Das Default-Mandat kann nicht gelöscht werden."));
            return;
        }

        // Prüfe ob Benutzer mit diesem Mandat existieren
        boolean hasUsers = users.stream()
                .anyMatch(u -> selectedMandat.equalsIgnoreCase(u.getMandat()));

        if (hasUsers) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                            "Mandat kann nicht gelöscht werden, da noch Benutzer zugeordnet sind."));
            return;
        }

        mandate.remove(selectedMandat);
        selectedMandat = mandate.isEmpty() ? null : mandate.get(0);

        context.addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Mandat gelöscht."));

        log.debug("Deleted mandat: {}", selectedMandat);
    }

    /**
     * Gibt eine sortierte Kopie aller Mandate zurück.
     * Die Kopie stellt sicher, dass JSF/PrimeFaces die Änderungen in SelectItems erkennt.
     */
    public List<String> getAllMandate() {
        List<String> sortedMandate = new ArrayList<>(mandate);
        sortedMandate.sort(String::compareTo);
        return sortedMandate;
    }
}

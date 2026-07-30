/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.anforderungen.entity.Howto;
import ch.plaintext.anforderungen.repository.HowtoRepository;
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
import java.util.List;

/**
 * Backing Bean for howtos.xhtml
 * Manages Howtos - reusable instructions for Claude automation
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Scope("session")
@Component
@Data
public class HowtoBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    private PlaintextSecurity plaintextSecurity;

    @Autowired
    private HowtoRepository howtoRepository;

    private List<Howto> howtos = new ArrayList<>();
    private Howto selected;
    private Howto editHowto;

    @PostConstruct
    public void init() {
        loadHowtos();
    }

    public void loadHowtos() {
        try {
            String mandat = plaintextSecurity.getMandat();
            howtos = howtoRepository.findByMandat(mandat);
            log.debug("Loaded {} howtos for mandat {}", howtos.size(), mandat);
        } catch (Exception e) {
            log.error("Error loading howtos", e);
            addErrorMessage("Fehler beim Laden der Howtos: " + e.getMessage());
        }
    }

    public void newHowto() {
        editHowto = new Howto();
        editHowto.setMandat(plaintextSecurity.getMandat());
        editHowto.setActive(true);
        selected = null;
    }

    public void editSelected() {
        if (selected != null) {
            editHowto = selected;
        }
    }

    public void save() {
        try {
            if (editHowto == null) {
                addErrorMessage("Kein Howto ausgewählt");
                return;
            }

            // Validate
            if (editHowto.getName() == null || editHowto.getName().trim().isEmpty()) {
                addErrorMessage("Name ist erforderlich");
                return;
            }

            if (editHowto.getText() == null || editHowto.getText().trim().isEmpty()) {
                addErrorMessage("Text ist erforderlich");
                return;
            }

            if (editHowto.getText().length() > 2000) {
                addErrorMessage("Text darf maximal 2000 Zeichen lang sein");
                return;
            }

            // Set mandat if not set
            if (editHowto.getMandat() == null) {
                editHowto.setMandat(plaintextSecurity.getMandat());
            }

            // Save
            Howto saved = howtoRepository.save(editHowto);
            log.info("Saved howto: id={}, name={}", saved.getId(), saved.getName());

            addSuccessMessage("Howto erfolgreich gespeichert");
            loadHowtos();
            editHowto = null;
            selected = null;

        } catch (Exception e) {
            log.error("Error saving howto", e);
            addErrorMessage("Fehler beim Speichern: " + e.getMessage());
        }
    }

    public void delete() {
        try {
            if (selected == null) {
                addErrorMessage("Kein Howto ausgewählt");
                return;
            }

            howtoRepository.delete(selected);
            log.info("Deleted howto: id={}, name={}", selected.getId(), selected.getName());

            addSuccessMessage("Howto gelöscht");
            loadHowtos();
            selected = null;
            editHowto = null;

        } catch (Exception e) {
            log.error("Error deleting howto", e);
            addErrorMessage("Fehler beim Löschen: " + e.getMessage());
        }
    }

    public void cancel() {
        editHowto = null;
        selected = null;
    }

    private void addSuccessMessage(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", message));
    }

    private void addErrorMessage(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", message));
    }
}

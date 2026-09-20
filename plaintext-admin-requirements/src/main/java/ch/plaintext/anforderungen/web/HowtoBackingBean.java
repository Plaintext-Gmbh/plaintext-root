/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.anforderungen.entity.Howto;
import ch.plaintext.anforderungen.repository.HowtoRepository;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
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
 *
 * <h2>Why field injection stays here (java:S6813, card 1273)</h2>
 *
 * <p>This bean is <b>session-scoped and serializable</b> — the one case in which constructor
 * injection is the wrong answer. On a deserialization <b>no constructor runs</b>: a service
 * field set only through the
 * constructor stays {@code null} forever, and being {@code final} nothing can set it afterwards,
 * not even the context. A {@code NotSerializableException} would thereby turn into a permanent
 * {@code NullPointerException} (cards 915/1246). Field injection ({@code @Autowired}, not
 * {@code final}) lets the context refill the field after a deserialization — that is the house
 * rule, and {@code PlaintextSessionBeanSerialisierbarTest} enforces it as a build-breaking
 * guard.</p>
 *
 * <p>{@code java:S6813} demands the exact opposite at these fields, so both cannot hold at once.
 * The guard wins: it protects against a defect that is silent and permanent, the rule protects a
 * style. The suppression sits on the class because every injected service of such a bean falls
 * under the house rule — card 1273 carries the measurement and the decision.</p>
 */
@Slf4j
@Scope("session")
@Component
@Data
@SuppressWarnings("java:S6813") // begruendet im Klassenkommentar oben (Karte 1273) — nicht umbauen
public class HowtoBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    private PlaintextSecurity plaintextSecurity;

    @Autowired
    private transient HowtoRepository howtoRepository;

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
        FacesMessages.info("Erfolg", message);
    }

    private void addErrorMessage(String message) {
        FacesMessages.error("Fehler", message);
    }
}

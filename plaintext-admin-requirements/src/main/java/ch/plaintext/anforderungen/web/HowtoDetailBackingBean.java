/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.anforderungen.entity.Howto;
import ch.plaintext.anforderungen.repository.HowtoRepository;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * Backing Bean for mobile-friendly Howto detail page
 * Simplified version showing only Name and Active status
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
@Scope("session")
@Slf4j
public class HowtoDetailBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    private final HowtoRepository howtoRepository;

    @Getter @Setter
    private Long howtoId;

    @Getter @Setter
    private Howto howto;

    // Edit mode flags
    @Getter @Setter
    private boolean editingName = false;

    @Getter @Setter
    private boolean editingActive = false;

    // Temporary edit values
    @Getter @Setter
    private String tempName;

    @Getter @Setter
    private Boolean tempActive;

    public HowtoDetailBackingBean(HowtoRepository howtoRepository) {
        this.howtoRepository = howtoRepository;
    }

    /**
     * preRenderView listener (session-scoped): loads the Howto from the {@code id} viewParam on every GET.
     * viewParams are set before preRenderView, therefore {@code howtoId} is available here. The isPostback
     * guard prevents a reload on Ajax postbacks (the editing state is preserved).
     */
    public void onLoad() {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        loadHowto();
    }

    private void loadHowto() {
        if (howtoId != null) {
            try {
                howto = howtoRepository.findById(howtoId).orElse(null);
                if (howto == null) {
                    log.warn("Howto not found: {}", howtoId);
                    addMessage("Howto nicht gefunden", FacesMessage.SEVERITY_ERROR);
                } else {
                    log.info("Loaded howto {} for detail view", howtoId);
                }
            } catch (Exception e) {
                log.error("Error loading howto: {}", howtoId, e);
                addMessage("Fehler beim Laden: " + e.getMessage(), FacesMessage.SEVERITY_ERROR);
            }
        } else {
            log.warn("No howto ID provided");
            addMessage("Keine Howto-ID angegeben", FacesMessage.SEVERITY_ERROR);
        }
    }

    // Edit Name
    public void startEditName() {
        tempName = howto.getName();
        editingName = true;
    }

    public void saveName() {
        try {
            if (tempName == null || tempName.trim().isEmpty()) {
                addMessage("Name darf nicht leer sein", FacesMessage.SEVERITY_ERROR);
                return;
            }

            if (tempName.length() > 200) {
                addMessage("Name darf maximal 200 Zeichen lang sein", FacesMessage.SEVERITY_ERROR);
                return;
            }

            howto.setName(tempName);
            howtoRepository.save(howto);
            editingName = false;
            addMessage("Name erfolgreich gespeichert", FacesMessage.SEVERITY_INFO);
            log.info("Updated howto {} name to: {}", howto.getId(), tempName);
        } catch (Exception e) {
            log.error("Error saving name", e);
            addMessage("Fehler beim Speichern: " + e.getMessage(), FacesMessage.SEVERITY_ERROR);
        }
    }

    public void cancelEditName() {
        editingName = false;
        tempName = null;
    }

    // Edit Active
    public void startEditActive() {
        tempActive = howto.getActive();
        editingActive = true;
    }

    public void saveActive() {
        try {
            howto.setActive(tempActive);
            howtoRepository.save(howto);
            editingActive = false;
            addMessage("Aktiv-Status erfolgreich gespeichert", FacesMessage.SEVERITY_INFO);
            log.info("Updated howto {} active status to: {}", howto.getId(), tempActive);
        } catch (Exception e) {
            log.error("Error saving active status", e);
            addMessage("Fehler beim Speichern: " + e.getMessage(), FacesMessage.SEVERITY_ERROR);
        }
    }

    public void cancelEditActive() {
        editingActive = false;
        tempActive = null;
    }

    private void addMessage(String message, FacesMessage.Severity severity) {
        FacesMessages.meldung(severity, message, null);
    }
}

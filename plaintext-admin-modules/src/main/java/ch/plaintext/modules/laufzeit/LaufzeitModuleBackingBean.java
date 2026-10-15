/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.laufzeit;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
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
 * Karte 1515: Laufzeit-Module in der Modul-Ansicht (module.html). Die Pruefungen (nur ROOT, Lader-Parser,
 * atomares Schreiben, Audit) stehen im {@link LaufzeitModuleService}, nicht hier.
 *
 * <p>Feldinjektion mit {@code transient} wie {@code ModulesBackingBean} (Sitzungs-Bean, Karte 1273).</p>
 */
@Slf4j
@Scope("session")
@Component
@Data
@SuppressWarnings("java:S6813") // wie ModulesBackingBean (Karte 1273)
public class LaufzeitModuleBackingBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Autowired
    private transient LaufzeitModuleService service;

    private LaufzeitModuleService.Stand stand;
    private List<String> auswahl = new ArrayList<>();
    private String vorschau;

    /** preRenderView: feuert auch im Ajax-Postback, dort darf die Auswahl nicht zurueckgesetzt werden. */
    public void load() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc != null && fc.isPostback()) {
            return;
        }
        neuLesen();
    }

    void neuLesen() {
        try {
            stand = service.stand();
            auswahl = new ArrayList<>(stand.konfig().stream()
                    .filter(e -> !e.endsWith(" (Bibliothek)"))
                    .map(e -> e.substring(0, e.lastIndexOf(':')))
                    .filter(n -> stand.registry().stream().anyMatch(r -> r.name().equals(n)))
                    .toList());
            vorschau = null;
        } catch (RuntimeException ex) {
            stand = null;
            meldung(FacesMessage.SEVERITY_ERROR, ex.getMessage());
        }
    }

    public void vorschauErzeugen() {
        try {
            vorschau = service.entwurf(auswahl);
        } catch (Exception ex) {
            vorschau = null;
            meldung(FacesMessage.SEVERITY_ERROR, "Vorschau: " + ex.getMessage());
        }
    }

    public void schreiben() {
        try {
            List<String> neu = service.schreiben(auswahl);
            meldung(FacesMessage.SEVERITY_INFO, "Konfig geschrieben (" + neu.size() + " Jars). Wirksam beim naechsten Start.");
            neuLesen();
        } catch (Exception ex) {
            log.warn("Laufzeit-Module schreiben abgewiesen: {}", ex.getMessage());
            meldung(FacesMessage.SEVERITY_ERROR, "Nicht geschrieben: " + ex.getMessage());
        }
    }

    public void rollback() {
        try {
            List<String> wieder = service.rollback();
            meldung(FacesMessage.SEVERITY_WARN, "Vorheriger Stand zurueck (" + wieder.size() + " Jars). Wirksam beim naechsten Start.");
            neuLesen();
        } catch (Exception ex) {
            log.warn("Laufzeit-Module Rollback abgewiesen: {}", ex.getMessage());
            meldung(FacesMessage.SEVERITY_ERROR, "Rollback nicht moeglich: " + ex.getMessage());
        }
    }

    private void meldung(FacesMessage.Severity s, String text) {
        if (FacesContext.getCurrentInstance() != null) {
            FacesMessages.meldung(s, text, null);
        }
    }
}

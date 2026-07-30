/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.rollenzuteilung.entity.Rollenzuteilung;
import ch.plaintext.rollenzuteilung.service.RollenzuteilungService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

@Component
@Scope("session")
@Getter
@Setter
@Slf4j
public class RollenzuteilungBackingBean implements Serializable {

    private final RollenzuteilungService service;
    private final PlaintextSecurity security;

    private List<Rollenzuteilung> rollenzuteilungen;
    private Rollenzuteilung selected;
    private boolean admin;

    public RollenzuteilungBackingBean(RollenzuteilungService service, PlaintextSecurity security) {
        this.service = service;
        this.security = security;
    }

    /**
     * preRenderView-Listener (session-scoped): setzt die Rolle, sperrt Nicht-Admins per Redirect aus und
     * laedt die Daten FRISCH bei jedem GET. Der isPostback-Guard verhindert das Neuladen bei Ajax-Postbacks.
     * Ersetzt das fruehere @PostConstruct init() + checkAccess().
     */
    public void onLoad() {
        admin = security.ifGranted("ROLE_ADMIN") || security.ifGranted("ROLE_ROOT");
        if (!admin) {
            try {
                FacesContext.getCurrentInstance().getExternalContext().redirect("/index.xhtml");
            } catch (Exception e) {
                log.error("Redirect failed", e);
            }
            return;
        }
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        loadData();
    }

    private void loadData() {
        try {
            rollenzuteilungen = service.getAllRollenzuteilungenForCurrentUser();
        } catch (Exception e) {
            log.error("Error loading rollenzuteilungen", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Daten konnten nicht geladen werden");
        }
    }

    public void select() {
        // Selected in UI
    }

    public void clearSelection() {
        selected = null;
    }

    public void newRollenzuteilung() {
        selected = new Rollenzuteilung();
        selected.setMandat(security.getMandat());
        selected.setActive(true);
    }

    public void save() {
        if (selected == null) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Rollenzuteilung ausgewählt");
            return;
        }

        if (selected.getUsername() == null || selected.getUsername().trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Benutzername ist erforderlich");
            return;
        }

        if (selected.getRoleName() == null || selected.getRoleName().trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Rolle ist erforderlich");
            return;
        }

        try {
            service.save(selected);
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Rollenzuteilung gespeichert");
            loadData();
        } catch (Exception e) {
            log.error("Error saving rollenzuteilung", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Speichern fehlgeschlagen");
        }
    }

    public void delete() {
        if (selected == null) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Rollenzuteilung ausgewählt");
            return;
        }

        try {
            service.delete(selected.getId());
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Rollenzuteilung gelöscht");
            selected = null;
            loadData();
        } catch (Exception e) {
            log.error("Error deleting rollenzuteilung", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Löschen fehlgeschlagen");
        }
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }

    public List<String> getAvailableRoles() {
        return List.of("ROLE_USER", "ROLE_ADMIN", "ROLE_ROOT", "ROLE_MANAGER", "ROLE_VIEWER", "ROLE_POSTKONTO", "ROLE_PRIVATAUSGABEN");
    }
}

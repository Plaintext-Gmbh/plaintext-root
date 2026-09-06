/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.sessions.entity.UserSession;
import ch.plaintext.sessions.service.SessionAuditServiceImpl;
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
public class SessionsBackingBean implements Serializable {

    private final SessionAuditServiceImpl sessionService;
    private final PlaintextSecurity security;

    private List<UserSession> sessions;
    private UserSession selected;
    private boolean root;

    public SessionsBackingBean(SessionAuditServiceImpl sessionService, PlaintextSecurity security) {
        this.sessionService = sessionService;
        this.security = security;
    }

    /**
     * preRenderView listener (session-scoped): sets the role, locks out non-admins/non-ROOT via a
     * redirect and loads the session list FRESH on every GET. The isPostback guard prevents the reload
     * on Ajax postbacks. Replaces the former @PostConstruct init() + checkAccess().
     */
    public void onLoad() {
        root = security.ifGranted("ROLE_ROOT");
        if (!security.ifGranted("ROLE_ADMIN") && !root) {
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
            if (root) {
                // Root sees all active sessions
                sessions = sessionService.getAllActiveSessions();
            } else {
                // Admin sees only sessions from their mandat
                sessions = sessionService.getActiveSessionsByMandat(security.getMandat());
            }
            log.debug("Loaded {} active sessions", sessions != null ? sessions.size() : 0);
        } catch (Exception e) {
            log.error("Error loading sessions", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Sitzungen konnten nicht geladen werden");
        }
    }

    public void select() {
        // Selected in UI
    }

    public void clearSelection() {
        selected = null;
    }

    public void refresh() {
        loadData();
        selected = null;
        addMessage(FacesMessage.SEVERITY_INFO, "Aktualisiert", "Sitzungsliste wurde aktualisiert");
    }

    public void forceLogout() {
        if (selected == null) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Sitzung ausgewählt");
            return;
        }

        try {
            sessionService.forceLogout(selected.getSessionId());
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Benutzer wurde abgemeldet");
            loadData();
            selected = null;
        } catch (Exception e) {
            log.error("Error forcing logout", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Abmeldung fehlgeschlagen");
        }
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesMessages.meldung(severity, summary, detail);
    }
}

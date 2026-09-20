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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

/**
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
@Component
@Scope("session")
@Getter
@Setter
@Slf4j
@SuppressWarnings("java:S6813") // begruendet im Klassenkommentar oben (Karte 1273) — nicht umbauen
public class SessionsBackingBean implements Serializable {

    // Feldinjektion, nicht Konstruktorinjektion (Karte 1269). Diese Bohne ist session-scoped und
    // serialisierbar: bei einer Deserialisierung laeuft KEIN Konstruktor. Als `final` gesetzte
    // Dienste blieben danach dauerhaft null, und final liesse sich auch nachtraeglich nicht mehr
    // setzen — aus einer NotSerializableException wuerde eine dauerhafte NullPointerException
    // (Karten 915/1246). Ueber @Autowired fuellt der Kontext die Felder nach dem Aufwachen wieder.
    // Das ist die Hausregel; java:S6813 gilt hier bewusst nicht.
    @Autowired
    private transient SessionAuditServiceImpl sessionService;
    @Autowired
    private transient PlaintextSecurity security;

    private List<UserSession> sessions;
    private UserSession selected;
    private boolean root;

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

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.boot.plugins.log.Log;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.framework.PlaintextRoleRegistry;
import ch.plaintext.framework.PrivilegedRoleRules;
import ch.plaintext.rollenzuteilung.entity.Rollenzuteilung;
import ch.plaintext.rollenzuteilung.service.RollenzuteilungService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

@Component
@Scope("session")
@Getter
@Setter
@Slf4j
public class RollenzuteilungBackingBean implements Serializable {

    private final RollenzuteilungService service;
    private final PlaintextSecurity security;

    /**
     * Role registry (module role registration): supplies the roles declared by the modules for the
     * selection. Optional ({@code null} allowed), so that contexts without a registry keep
     * working.
     */
    private final transient PlaintextRoleRegistry roleRegistry;

    private List<Rollenzuteilung> rollenzuteilungen;
    private Rollenzuteilung selected;
    private boolean admin;

    public RollenzuteilungBackingBean(RollenzuteilungService service, PlaintextSecurity security) {
        this(service, security, null);
    }

    @Autowired
    public RollenzuteilungBackingBean(RollenzuteilungService service, PlaintextSecurity security,
                                      @Nullable PlaintextRoleRegistry roleRegistry) {
        this.service = service;
        this.security = security;
        this.roleRegistry = roleRegistry;
    }

    /**
     * preRenderView listener (session-scoped): sets the role, locks non-admins out via redirect and
     * loads the data FRESH on every GET. The isPostback guard prevents a reload on Ajax postbacks.
     * Replaces the former @PostConstruct init() + checkAccess().
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

        if (!darfVergeben(selected.getRoleName())) {
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
        FacesMessages.meldung(severity, summary, detail);
    }

    /**
     * May the current actor grant this role?
     *
     * <p>Role assignment is open to ADMIN and ROOT — this is the place where an admin hands out the
     * <b>module roles</b>, and that is exactly what it is meant to be open for. Privileged roles
     * ({@code root}, {@code admin}, {@code PROPERTY_*}) stay reserved for root: otherwise an admin
     * could obtain the very role here that the user administration denies them.</p>
     *
     * <p>An assignment of the same role that has already been saved stays editable (existing
     * data).</p>
     *
     * @param roleName the role to be granted
     * @return {@code true} if saving is allowed
     */
    private boolean darfVergeben(String roleName) {
        if (security.ifGranted("ROLE_ROOT") || !PrivilegedRoleRules.isPrivileged(roleName)) {
            return true;
        }
        boolean bestand = service
                .findByUsernameAndMandatAndRole(selected.getUsername(), selected.getMandat(), roleName)
                .isPresent();
        if (bestand) {
            return true;
        }
        log.warn("SECURITY: Nicht-ROOT-Akteur versuchte, privilegierte Rolle '{}' an '{}' (Mandant {}) "
                + "zu vergeben — abgelehnt.", roleName, Log.mail(selected.getUsername()), selected.getMandat());
        addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", PrivilegedRoleRules.rejectionMessage(roleName));
        return false;
    }

    /**
     * The selectable roles (authority format {@code ROLE_<UPPERCASE>}): the union of the roles
     * DECLARED by the modules ({@link PlaintextRoleRegistry}) and the roles already assigned in the
     * database (existing data — roles without a declaration are not lost).
     * Replaces the former hard-coded role list.
     *
     * @return sorted, deduplicated role names
     */
    public List<String> getAvailableRoles() {
        TreeSet<String> roles = new TreeSet<>();
        if (roleRegistry != null) {
            try {
                roles.addAll(roleRegistry.getDeclaredAuthorityNames());
            } catch (Exception e) {
                log.error("Error reading declared roles from registry", e);
            }
        }
        try {
            for (String bestand : service.getDistinctRoleNames()) {
                if (bestand != null && !bestand.trim().isEmpty()) {
                    roles.add(bestand.trim());
                }
            }
        } catch (Exception e) {
            log.error("Error reading existing role names", e);
        }
        return new ArrayList<>(roles);
    }
}

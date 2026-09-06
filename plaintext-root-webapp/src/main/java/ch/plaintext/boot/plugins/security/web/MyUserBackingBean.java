/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.boot.plugins.log.Log;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.audit.DestructiveActionAuditService;
import ch.plaintext.boot.plugins.security.magiclink.MagicLinkService;
import ch.plaintext.boot.plugins.security.model.MyRememberMe;
import ch.plaintext.boot.table.TableColumn;
import ch.plaintext.boot.table.TableSettings;
import ch.plaintext.boot.table.TableStateStore;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.model.UserMandate;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import ch.plaintext.framework.PlaintextRole;
import ch.plaintext.framework.PlaintextRoleRegistry;
import ch.plaintext.framework.PrivilegedRoleRules;
import jakarta.annotation.PostConstruct;
import jakarta.faces.model.SelectItem;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Scope("session")
@Component
@Data
public class MyUserBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    /** Column width of {@code destructive_action_audit.detail}; longer texts are truncated. */
    private static final int AUDIT_DETAIL_MAX = 2000;

    /**
     * SECURITY (card 314, item 7): the central {@link PasswordEncoder} bean instead of a local
     * {@code new BCryptPasswordEncoder()}. The local call would have kept the Spring default cost
     * factor 10, while the bean in {@code PlaintextSecurityConfig} stands at 12 — the
     * cost factors would therefore have drifted apart depending on the code path.
     */
    private transient PasswordEncoder passwordEncoder;
    private boolean remlistcolapsed = false;
    private MyUserEntity selected;
    private String myUserPw;
    private MyRememberMe selectedRememberMe;

    private MyUserRepository repo;

    /**
     * Role registry (card: module role registration): supplies the roles declared by the modules
     * for the selection in the user dialog. Wired optionally, so that
     * contexts without a registry (e.g. lean tests) keep working.
     */
    private transient PlaintextRoleRegistry roleRegistry;

    private MyRememberMeRepository rememberMeRepo;

    private PlaintextSecurity plaintextSecurity;

    private transient UserMandateRepository userMandateRepo;

    private transient MagicLinkService magicLinkService;

    /**
     * SECURITY (forensics 23.08.2026): audit writer for role changes and user deletions. Like
     * {@link #roleRegistry} wired optionally, so that lean contexts (tests without JPA) can still
     * build the bean — if it is missing, only the {@code log.warn} remains as a trace.
     */
    private transient DestructiveActionAuditService auditService;

    /**
     * SECURITY (forensics 23.08.2026): the confirmation prompt shown when privileged roles are
     * revoked by a root actor. {@code null} = no prompt pending.
     */
    private String rollenEntzugFrage;

    /**
     * SECURITY (forensics 23.08.2026): {@code true} as soon as the root actor has confirmed the revocation in the dialog.
     * Is reset after every completed operation (save, cancel, change of selection),
     * so that a confirmation never carries over to a second, unseen revocation.
     */
    private boolean rollenEntzugBestaetigt;

    /**
     * Constructor injection instead of field injection (Sonar S6813): the dependencies are thereby
     * fixed before {@code @PostConstruct} already, and the bean can be built without Spring.
     *
     * @param passwordEncoder central encoder bean (cost factor 12)
     * @param repo            user repository
     * @param roleRegistry    role registry; optional, may be {@code null}
     * @param rememberMeRepo  remember-me repository
     * @param plaintextSecurity security context (roles, tenant)
     * @param userMandateRepo repository of the additional tenants
     * @param magicLinkService sending of the magic links
     * @param auditService    audit log for role changes/deletions; optional, may be {@code null}
     * @param tableStateStore storage of the column selection (Karte 1077); optional, may be {@code null}
     */
    @Autowired
    public MyUserBackingBean(PasswordEncoder passwordEncoder,
                             MyUserRepository repo,
                             @Nullable PlaintextRoleRegistry roleRegistry,
                             MyRememberMeRepository rememberMeRepo,
                             PlaintextSecurity plaintextSecurity,
                             UserMandateRepository userMandateRepo,
                             MagicLinkService magicLinkService,
                             @Nullable DestructiveActionAuditService auditService,
                             @Nullable TableStateStore tableStateStore) {
        this.passwordEncoder = passwordEncoder;
        this.repo = repo;
        this.roleRegistry = roleRegistry;
        this.rememberMeRepo = rememberMeRepo;
        this.plaintextSecurity = plaintextSecurity;
        this.userMandateRepo = userMandateRepo;
        this.magicLinkService = magicLinkService;
        this.auditService = auditService;
        this.tableStateStore = tableStateStore;
    }

    /** Additional tenants of the selected user (multi-tenant), editable in the dialog. */
    private List<String> selectedZusatzMandate = new ArrayList<>();

    private List<MyUserEntity> users = new ArrayList<>();
    private List<MyRememberMe> rememberMes = new ArrayList<>();

    /** Filled by the table (filteredValue) - without this field PrimeFaces loses the filter while sorting. */
    private List<MyUserEntity> gefilterteUsers;

    // ------------------------------------------------------------------ Column selection

    /**
     * Page key of this table in the user's stored table states ({@code UserPreference}). It is
     * part of stored records and must never change. The same key was used by the previous
     * storage ({@code UserPreference.tabellenSpalten}); the store reads that as a fallback, so
     * a selection saved before Karte 1077 comes back unchanged.
     */
    static final String TABELLE = "useradmin";

    /**
     * All columns that can be switched off, in display order: key, header text, and no fixed
     * width (the table runs with {@code reflow} and auto layout, hence {@code mitBreiten=false}).
     *
     * <p>The column "Bearbeiten" is missing here on purpose: it is the only way to open a
     * user. If it could be deselected, one could render the administration unusable
     * and would then have no way back.
     */
    private static final List<TableColumn> SPALTEN = List.of(
            new TableColumn("id", "ID", 0),
            new TableColumn("username", "Benutzername", 0),
            new TableColumn("vorname", "Vorname", 0),
            new TableColumn("nachname", "Nachname", 0),
            new TableColumn("mandat", "Mandat", 0),
            new TableColumn("startpage", "Startseite", 0),
            new TableColumn("remember", "Remember-Me", 0),
            new TableColumn("impersonate", "Impersonate", 0));

    /**
     * Karte 1077: the display state of the table — the same building block as
     * {@code pt:tableSettings}, here only for the visibility, driven by the checkbox menu that
     * Daniel asked for on 25.08.2026. Loading and saving run through {@link #tableStateStore};
     * the state is keyed per user and tenant.
     */
    private final TableSettings anzeige = new TableSettings(TABELLE, false);

    /**
     * Wired optionally: contexts without user settings (lean tests) shall be able to keep
     * building the bean. If it is missing, the default applies permanently and nothing is saved.
     */
    private transient TableStateStore tableStateStore;

    /** The selection for the checkbox menu above the table. */
    public List<SelectItem> getSpaltenAuswahl() {
        return anzeige.getColumnItems();
    }

    /** The visible column keys — value of the checkbox menu. */
    public List<String> getSichtbareSpalten() {
        return anzeige.getVisibleColumns();
    }

    /**
     * An <b>empty</b> selection is a valid statement ("I want none of these columns") and is
     * kept as such — {@link TableSettings#setVisibleColumns(List)} marks every column that is
     * not in the list as hidden.
     */
    public void setSichtbareSpalten(List<String> spalten) {
        anzeige.setVisibleColumns(spalten);
    }

    /** @return {@code true} if the column is shown for this user */
    public boolean spalteSichtbar(String schluessel) {
        return anzeige.isVisible(schluessel);
    }

    /**
     * Applies the column selection to the user settings <b>immediately</b>.
     *
     * <p>Request from Daniel, 25.08.2026: "whenever one opens it there and makes the column
     * selection, it should be saved". That is why this hangs off the {@code change} event of the menu and
     * not off a save button - a button that one can forget is exactly what
     * was not wanted here.
     */
    public void spaltenGeaendert() {
        if (tableStateStore == null) {
            log.debug("Spaltenauswahl nicht gespeichert - keine Ablage verfuegbar");
            return;
        }
        anzeige.persist();
        log.debug("Spaltenauswahl fuer {} gespeichert: {}", TABELLE, anzeige.getVisibleColumns());
    }

    /**
     * Fetches the stored state; without a stored entry the default (all columns) stays in
     * effect. A {@code null} store is allowed and leaves the bean usable without saving.
     */
    private void ladeSpaltenauswahl() {
        anzeige.init(tableStateStore, SPALTEN);
    }

    /**
     * The tenants that really occur in the table - for the multi-selection filter.
     *
     * <p>Deliberately taken from the loaded users and not from all known tenants: a
     * filter value that leads to zero rows is only confusing for the user. A user
     * without a tenant appears as an empty entry and thereby stays findable.
     */
    public List<SelectItem> getMandatFilterAuswahl() {
        return users.stream()
                .map(u -> u.getMandat() == null ? "" : u.getMandat())
                .distinct()
                .sorted()
                .map(m -> new SelectItem(m, m.isEmpty() ? "(ohne Mandant)" : m))
                .toList();
    }

    @PostConstruct
    public void init() {
        log.info("Loading users for user administration");
        ladeSpaltenauswahl();

        users.clear();

        // Root sees all users, admin only those of their own tenant
        if (isRoot()) {
            users.addAll(repo.findAll());
            log.info("Loaded {} users (all - root access)", users.size());
        } else if (isAdmin()) {
            String currentMandat = plaintextSecurity.getMandat();
            List<MyUserEntity> allUsers = repo.findAll();
            users.addAll(allUsers.stream()
                .filter(u -> currentMandat.equals(u.getMandat()))
                .collect(Collectors.toList()));
            log.info("Loaded {} users (filtered by mandate: {})", users.size(), currentMandat);
        } else {
            log.warn("User is neither admin nor root - should not access user administration");
        }

        rememberMes.clear();
        rememberMes.addAll(rememberMeRepo.findAll());
    }

    /**
     * Checks whether the current user has the root role.
     */
    public boolean isRoot() {
        return plaintextSecurity != null && plaintextSecurity.ifGranted("ROLE_root");
    }

    /**
     * Checks whether the current user has the admin role.
     */
    public boolean isAdmin() {
        return plaintextSecurity != null && plaintextSecurity.ifGranted("ROLE_admin");
    }

    /**
     * Checks whether the current user has access to the user administration.
     * Is called on preRenderView.
     */
    public void checkAccess() {
        if (!isRoot() && !isAdmin()) {
            log.warn("SECURITY: User attempted to access user administration without proper role");
            try {
                FacesContext.getCurrentInstance().getExternalContext().redirect("access-denied.xhtml");
            } catch (Exception e) {
                log.error("Error redirecting to access denied page", e);
            }
        }
    }

    public void deleteRememberMe() {
        rememberMeRepo.delete(selectedRememberMe);
        init();
    }

    /**
     * Opens the dialog for a new user.
     *
     * <p>DATA QUALITY (forensics 23.08.2026): the empty entity is <b>no longer persisted immediately</b>.
     * Previously every click on "Neuer Benutzer" — an aborted one included — created an orphan row
     * with an empty {@code username} that the user administration never collected again.
     * The entity now stays transient until {@link #save()} actually writes it with a valid user name and
     * password; {@code save()} copes with {@code id == null}
     * (insert instead of update). The {@code init()} is dropped as well: there is nothing to reload.</p>
     */
    public void newUser() {
        selected = new MyUserEntity();
        // Set default mandate for new user (can be changed in the dialog)
        selected.setMandat("default");
        select();
    }

    public void select() {
        log.debug("SELECT called - selected: {}", selected != null ? selected.getId() + "/" + Log.mail(selected.getUsername()) : "null");
        resetRollenEntzug();
        if (selected != null) {
            myUserPw = selected.getPassword();
            loadZusatzMandate();
        }
    }

    /**
     * Opens the edit dialog for the given row (row button in the
     * user list): sets the selection and loads the dialog data like {@link #select()}.
     *
     * @param user the user from the clicked table row
     */
    public void edit(MyUserEntity user) {
        selected = user;
        select();
    }

    public void clearSelection() {
        log.debug("CLEAR SELECTION called");
        selected = null;
        resetRollenEntzug();
    }

    public void validateUsername() {
        if (selected == null || selected.getUsername() == null || selected.getUsername().trim().isEmpty()) {
            return;
        }

        // Check whether the user name already exists (only for new users or on a change)
        MyUserEntity existingUser = repo.findByUsername(selected.getUsername());
        if (existingUser != null && !existingUser.getId().equals(selected.getId())) {
            FacesMessages.feld("username", FacesMessage.SEVERITY_ERROR, "Fehler", "Ein Benutzer mit dieser E-Mail-Adresse existiert bereits.");
        }
    }

    @Transactional
    public void save() {
        FacesContext context = FacesContext.getCurrentInstance();

        // Validate the e-mail format
        if (selected.getUsername() == null || selected.getUsername().trim().isEmpty()) {
            FacesMessages.error("Fehler", "Benutzername darf nicht leer sein.");
            context.validationFailed();
            return;
        }

        if (!EMAIL_PATTERN.matcher(selected.getUsername()).matches()) {
            FacesMessages.error("Fehler", "Benutzername muss eine gültige E-Mail-Adresse sein.");
            context.validationFailed();
            return;
        }

        // Check whether the user name already exists (only for new users or on a change)
        MyUserEntity existingUser = repo.findByUsername(selected.getUsername());
        if (existingUser != null && !existingUser.getId().equals(selected.getId())) {
            FacesMessages.error("Fehler", "Ein Benutzer mit dieser E-Mail-Adresse existiert bereits.");
            context.validationFailed();
            return;
        }

        // SECURITY (card 307, K1): snapshot of the PERSISTED roles (freshly fetched by ID from the DB, not the
        // `selected` that may already have been overwritten by the JSF model update) — the basis for the role allowlist,
        // so that existing data stays editable while a non-ROOT cannot grant a privileged role ANEW.
        Set<String> persistedRoles = new HashSet<>();
        if (selected.getId() != null) {
            MyUserEntity persisted = repo.findById(selected.getId()).orElse(null);
            if (persisted != null && persisted.getRoles() != null) {
                persistedRoles = new HashSet<>(persisted.getRoles());
            }
        }

        // Validate the password for new users or when the password has been changed
        boolean isNewUser = myUserPw == null || myUserPw.isEmpty();
        boolean passwordChanged = !selected.getPassword().isEmpty() && !selected.getPassword().equals(myUserPw);

        if (!selected.isPasswordless() && isNewUser && selected.getPassword().isEmpty()) {
            FacesMessages.error("Fehler", "Passwort darf bei einem neuen Benutzer nicht leer sein.");
            context.validationFailed();
            return;
        }

        if (selected.isPasswordless()) {
            selected.setPassword("");
        }

        // Synchronize roles from chips component (List) back to entity (Set)
        syncRolesFromListToSet();

        // SECURITY (card 307, K1): server-side role allowlist. A non-ROOT actor must not grant ANY
        // privileged roles ANEW through the user administration (which is legitimate for them). Which roles
        // those are is decided centrally by PrivilegedRoleRules: "root"/"admin" (administration rights that could
        // otherwise pass themselves on) and every "PROPERTY_*" role (tenant switch/cross-tenant).
        // Module roles are explicitly NOT privileged — granting them is the admin's job.
        // Roles already present on the persisted record stay permitted (existing data editable). The
        // server-side check is the actual fix; the free-text chips in the UI are only convenience.
        if (!isRoot() && !pruefeRollenAllowlist(context, persistedRoles)) {
            return;
        }

        // SECURITY (forensics 23.08.2026, K1): the REVOCATION side. pruefeRollenAllowlist() only sees what
        // was submitted — what is MISSING nobody saw so far. That is exactly how an administrator account
        // silently lost 'root' and 'admin' when a root actor saved from a phone with an empty role
        // selection. The difference `persisted \ submitted` is the only place where such a
        // loss becomes visible at all.
        Set<String> neueRollen = selected.getRoles() == null
                ? new HashSet<>() : new HashSet<>(selected.getRoles());
        Set<String> entzogenePrivilegierte = entzogenePrivilegierteRollen(persistedRoles, neueRollen);
        if (!entzogenePrivilegierte.isEmpty() && !pruefeRollenEntzug(context, entzogenePrivilegierte)) {
            return;
        }

        // Set default mandate if none is specified
        if (selected.getMandat() == null || selected.getMandat().trim().isEmpty()) {
            selected.setMandat("default");
            log.debug("No mandate specified for user {}, setting to 'default'", Log.mail(selected.getUsername()));
        }

        if (!selected.getPassword().isEmpty() && !selected.getPassword().startsWith("$2a$10")) {
            selected.setPassword(passwordEncoder.encode(selected.getPassword()));
        } else {
            selected.setPassword(myUserPw);
        }
        repo.save(selected);
        // TRACEABILITY (forensics 23.08.2026): log only after the write has succeeded —
        // otherwise the log claims a change that may never have happened.
        protokolliereRollenaenderung(persistedRoles, selected.getRoles());
        saveZusatzMandate();
        selected = null;
        resetRollenEntzug();
        init();
        FacesMessages.info("Erfolg", "Benutzer erfolgreich gespeichert.");
    }

    /**
     * Checks the roles selected on the form against {@link PrivilegedRoleRules}. Already
     * persisted roles are always permitted — only granting them ANEW is restricted.
     *
     * @param context        FacesContext for the error message
     * @param persistedRoles the roles of the persisted record (existing data)
     * @return {@code true} if saving is permitted
     */
    private boolean pruefeRollenAllowlist(FacesContext context, Set<String> persistedRoles) {
        for (String role : selected.getRoles()) {
            if (role == null || !PrivilegedRoleRules.isPrivileged(role) || persistedRoles.contains(role)) {
                continue;
            }
            log.warn("SECURITY (Karte 307, K1): Nicht-ROOT-Akteur versuchte, privilegierte Rolle '{}' "
                    + "an Benutzer '{}' zu vergeben — abgelehnt.", role, Log.mail(selected.getUsername()));
            FacesMessages.error("Fehler", PrivilegedRoleRules.rejectionMessage(role));
            context.validationFailed();
            return false;
        }
        return true;
    }

    /**
     * Which of the persisted roles would be REVOKED from the user by this save and are
     * privileged in the sense of {@link PrivilegedRoleRules}.
     *
     * <p>Tenant roles ({@code PROPERTY_MANDAT_*}) stay out of it: the tenant is a separate,
     * visible dialog field with its own semantics — a tenant change is no silent
     * loss of rights, and a confirmation prompt on every tenant change would devalue the warning.</p>
     *
     * @param persistiert roles of the persisted record
     * @param neu         roles after the form has been applied
     * @return the revoked privileged roles, sorted; never {@code null}
     */
    private static Set<String> entzogenePrivilegierteRollen(Set<String> persistiert, Set<String> neu) {
        Set<String> entzogen = new TreeSet<>();
        for (String role : persistiert) {
            if (role == null || neu.contains(role) || istMandatRolle(role)) {
                continue;
            }
            if (PrivilegedRoleRules.isPrivileged(role)) {
                entzogen.add(role);
            }
        }
        return entzogen;
    }

    /**
     * The revocation protection. A non-root actor must not revoke privileged roles at all;
     * a root actor may, but has to confirm it explicitly. The incident that
     * led to this card was exactly a root actor — a protection against non-root actors alone would
     * not have prevented it.
     *
     * @param context  FacesContext for the message
     * @param entzogen the revoked privileged roles (not empty)
     * @return {@code true} if saving is permitted
     */
    private boolean pruefeRollenEntzug(FacesContext context, Set<String> entzogen) {
        String rollen = String.join(", ", entzogen);
        String benutzer = selected.getUsername();
        if (!isRoot()) {
            log.warn("SECURITY (Rollen-Entzug): Nicht-ROOT-Akteur '{}' versuchte, dem Benutzer '{}' die "
                    + "privilegierte(n) Rolle(n) {} zu ENTZIEHEN — abgelehnt, nichts gespeichert.",
                    Log.mail(handelnderBenutzer()), Log.mail(benutzer), rollen);
            FacesMessages.error("Fehler", "Nur ROOT darf die Rolle(n) " + rollen + " entziehen. Die Änderung wurde NICHT "
                            + "gespeichert.");
            context.validationFailed();
            resetRollenEntzug();
            return false;
        }
        if (!rollenEntzugBestaetigt) {
            rollenEntzugFrage = "Dem Benutzer " + benutzer + " werden die Rollen " + rollen
                    + " entzogen — fortfahren?";
            log.warn("SECURITY (Rollen-Entzug): ROOT-Akteur '{}' entzieht dem Benutzer '{}' die "
                    + "privilegierte(n) Rolle(n) {} — Bestaetigung angefordert, noch NICHT gespeichert.",
                    Log.mail(handelnderBenutzer()), Log.mail(benutzer), rollen);
            context.validationFailed();
            return false;
        }
        return true;
    }

    /**
     * The root actor has affirmed the role revocation in the confirmation dialog: the same
     * {@link #save()} path, this time with the confirmation flag set.
     */
    public void bestaetigeRollenEntzugUndSpeichere() {
        rollenEntzugBestaetigt = true;
        rollenEntzugFrage = null;
        save();
    }

    /** The root actor has declined the role revocation: save nothing, close the prompt. */
    public void brichRollenEntzugAb() {
        log.warn("SECURITY (Rollen-Entzug): ROOT-Akteur '{}' hat den Rollen-Entzug abgebrochen — "
                + "nichts gespeichert.", Log.mail(handelnderBenutzer()));
        resetRollenEntzug();
        FacesMessages.info("Abgebrochen", "Der Rollen-Entzug wurde nicht gespeichert.");
    }

    /** Whether an unanswered prompt about a role revocation is currently pending (for the UI). */
    public boolean isRollenEntzugAusstehend() {
        return rollenEntzugFrage != null;
    }

    private void resetRollenEntzug() {
        rollenEntzugFrage = null;
        rollenEntzugBestaetigt = false;
    }

    /**
     * Logs a role change as {@code WARN} (before → after, acting user)
     * and additionally writes it into the {@code destructive_action_audit}. Without a change nothing
     * happens — otherwise every save of a start page field would drown the log.
     *
     * @param vorher  roles of the persisted record before the save
     * @param nachher roles after the save
     */
    private void protokolliereRollenaenderung(Set<String> vorher, Set<String> nachher) {
        Set<String> alt = vorher == null ? new TreeSet<>() : new TreeSet<>(vorher);
        Set<String> neu = nachher == null ? new TreeSet<>() : new TreeSet<>(nachher);
        if (alt.equals(neu)) {
            return;
        }
        Set<String> hinzugefuegt = new TreeSet<>(neu);
        hinzugefuegt.removeAll(alt);
        Set<String> entzogen = new TreeSet<>(alt);
        entzogen.removeAll(neu);

        log.warn("AUDIT Rollenaenderung: Benutzer '{}' (id={}) — vorher {} → nachher {} "
                        + "[vergeben: {}, entzogen: {}]; handelnder Benutzer: '{}'",
                Log.mail(selected.getUsername()), selected.getId(), alt, neu, hinzugefuegt, entzogen,
                Log.mail(handelnderBenutzer()));

        if (auditService != null) {
            auditService.logDestructiveAction("UI", "USER_ROLES_CHANGED", "MyUserEntity",
                    String.valueOf(selected.getId()),
                    kuerze("Benutzer '" + selected.getUsername() + "': vorher " + alt + " → nachher "
                            + neu + "; entzogen: " + entzogen + "; vergeben: " + hinzugefuegt
                            + "; durch: " + handelnderBenutzer()));
        }
    }

    /**
     * Deletes the selected user.
     *
     * <p>TRACEABILITY (forensics 23.08.2026): the deletion used to run entirely on {@code log.debug} —
     * in production (level INFO) it was therefore invisible and not present in the audit at all.
     * Now: {@code WARN} with user, ID, tenant, roles and acting user, plus an
     * entry in the {@code destructive_action_audit}.</p>
     */
    public void delete() {
        if (selected == null || selected.getId() == null) {
            log.warn("AUDIT Benutzerloeschung: durch '{}' angefordert, aber kein gespeicherter "
                    + "Benutzer ausgewaehlt — nichts geloescht.", Log.mail(handelnderBenutzer()));
            selected = null;
            resetRollenEntzug();
            init();
            return;
        }

        Long id = selected.getId();
        String username = selected.getUsername();
        String mandat = selected.getMandat();
        Set<String> rollen = selected.getRoles() == null ? new TreeSet<>() : new TreeSet<>(selected.getRoles());
        try {
            repo.delete(selected);
            log.warn("AUDIT Benutzerloeschung: '{}' (id={}, Mandat={}, Rollen {}) geloescht durch '{}'.",
                    Log.mail(username), id, mandat, rollen, Log.mail(handelnderBenutzer()));
            if (auditService != null) {
                auditService.logDestructiveAction("UI", "USER_DELETE", "MyUserEntity",
                        String.valueOf(id),
                        kuerze("Benutzer '" + username + "' (Mandat " + mandat + ") geloescht; Rollen "
                                + rollen + "; durch: " + handelnderBenutzer()));
            }
            FacesMessages.info("Erfolg", "Benutzer erfolgreich gelöscht.");
        } catch (Exception e) {
            log.error("AUDIT Benutzerloeschung FEHLGESCHLAGEN: '{}' (id={}) durch '{}'",
                    Log.mail(username), id, Log.mail(handelnderBenutzer()), e);
            FacesMessages.error("Fehler", "Fehler beim Löschen des Benutzers: " + e.getMessage());
        }

        selected = null;
        resetRollenEntzug();
        init();
    }

    /** The logged-in (resp. impersonating) user who triggers the change. */
    private String handelnderBenutzer() {
        if (plaintextSecurity == null) {
            return "unbekannt";
        }
        String user = plaintextSecurity.getUser();
        return user == null || user.isBlank() ? "unbekannt" : user;
    }

    /** Truncates an audit free text to the column width of {@code detail} (VARCHAR(2000)). */
    private static String kuerze(String text) {
        if (text == null || text.length() <= AUDIT_DETAIL_MAX) {
            return text;
        }
        return text.substring(0, AUDIT_DETAIL_MAX - 3) + "...";
    }

    /** Whether the role encodes the home tenant ({@code PROPERTY_MANDAT_*}). */
    private static boolean istMandatRolle(String role) {
        return role != null && role.toUpperCase(Locale.ROOT).startsWith("PROPERTY_MANDAT_");
    }

    public void onToggle() {
        this.remlistcolapsed = !this.remlistcolapsed;
    }

    /**
     * Generates a magic link and sends it to the e-mail address of the selected user.
     */
    public void sendMagicLink() {
        FacesContext context = FacesContext.getCurrentInstance();
        String username = selected.getUsername();
        HttpServletRequest request = (HttpServletRequest) context.getExternalContext().getRequest();
        boolean sent = magicLinkService.generateAndSend(username, request);
        if (sent) {
            FacesMessages.info("Erfolg", "Magic-Link an " + username + " gesendet.");
        } else {
            FacesMessages.warn("Warnung", "Magic-Link konnte nicht gesendet werden.");
        }
    }

    public boolean hasRememberMeEntries(String username) {
        return !rememberMeRepo.findAllByUsername(username).isEmpty();
    }

    @Transactional
    public void deleteRememberMeForUser(String username) {
        rememberMeRepo.deleteAllByUsername(username);
        init();
    }

    /**
     * All roles that can be offered in the user administration (lowercase, without the ROLE_ prefix):
     * the union of the roles DECLARED by the modules ({@link PlaintextRoleRegistry},
     * role registry pattern analogous to the menu system) and the roles already
     * GRANTED in the database (existing data — roles that no module declares any more stay
     * visible this way and are not lost).
     * Properties (PROPERTY_*) and tenant roles are filtered out.
     *
     * <p>Replaces the earlier runtime scan of the XHTML files for ifGranted patterns, which
     * only worked in a dev checkout (it read {@code src/main/resources} from the file system).</p>
     *
     * @return set of unique role names (lowercase, without the ROLE_ prefix)
     */
    public Set<String> getAvailableRoles() {
        Set<String> roles = new LinkedHashSet<>();

        // 1. roles declared by modules (registry)
        roles.addAll(extractRolesFromRegistry());

        // 2. extract roles from the database (existing data)
        roles.addAll(extractRolesFromDatabase());

        // 3. filter out properties and tenant roles
        return roles.stream()
                .filter(role -> !role.toLowerCase().startsWith("property_"))
                .filter(role -> !role.toLowerCase().contains("mandat"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The roles declared by the modules (normalized: lowercase, without the ROLE_ prefix).
     */
    private Set<String> extractRolesFromRegistry() {
        if (roleRegistry == null) {
            return new LinkedHashSet<>();
        }
        try {
            Set<String> declared = roleRegistry.getDeclaredRoleNames();
            return declared != null ? declared : new LinkedHashSet<>();
        } catch (Exception e) {
            log.error("Error reading declared roles from registry", e);
            return new LinkedHashSet<>();
        }
    }

    /**
     * The selection entries for the role SelectCheckboxMenu in the user dialog:
     * {@link #getAvailableRoles()} plus the roles currently set on the user (so that a
     * preselected role that is no longer declared anywhere stays visible in the menu).
     * Declared roles carry their description in the label.
     *
     * @return sorted selection entries
     */
    public List<RoleOption> getSelectableRoles() {
        Set<String> names = new TreeSet<>(getAvailableRoles());
        names.addAll(getSelectedRolesList().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet()));

        List<RoleOption> ret = new ArrayList<>();
        for (String name : names) {
            String description = roleRegistry != null ? roleRegistry.getDescription(name) : "";
            String label = description == null || description.isEmpty() ? name : name + " — " + description;
            ret.add(new RoleOption(name, label));
        }
        return ret;
    }

    /**
     * One entry of the role selection in the user dialog: technical value plus display label
     * (name, for declared roles incl. the description from the {@link PlaintextRole}).
     */
    @lombok.Value
    public static class RoleOption implements Serializable {
        String name;
        String label;
    }

    /**
     * Extracts all roles in use from the database
     */
    private Set<String> extractRolesFromDatabase() {
        Set<String> roles = new LinkedHashSet<>();

        try {
            List<MyUserEntity> allUsers = repo.findAll();
            for (MyUserEntity user : allUsers) {
                if (user.getRoles() != null) {
                    for (String role : user.getRoles()) {
                        // Filter out "mandat" roles (see MyUserDetailsService)
                        if (!role.contains("mandat")) {
                            // Remove the ROLE_ prefix if present and convert to lowercase
                            String normalizedRole = role.toUpperCase().startsWith("ROLE_")
                                ? role.substring(5).toLowerCase()
                                : role.toLowerCase();
                            roles.add(normalizedRole);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error extracting roles from database", e);
        }

        return roles;
    }

    /**
     * Getter for the roles as a list (for the p:chips component).
     * Converts the set into a list and filters out properties and tenant roles.
     */
    public List<String> getSelectedRolesList() {
        if (selected == null || selected.getRoles() == null) {
            return new ArrayList<>();
        }
        return selected.getRoles().stream()
                .filter(role -> !istImDialogAusgeblendet(role))
                .collect(Collectors.toList());
    }

    /**
     * Whether the role is deliberately hidden in the role menu of the dialog: properties
     * ({@code PROPERTY_*}) and tenant roles. They are neither displayed nor submitted there —
     * and must therefore not count as "deselected" when saving.
     *
     * @param role role name, may be {@code null}
     * @return {@code true} if the role is not visible in the dialog
     */
    private static boolean istImDialogAusgeblendet(String role) {
        if (role == null) {
            return true;
        }
        return role.toUpperCase(Locale.ROOT).startsWith("PROPERTY_")
                || role.toLowerCase(Locale.ROOT).contains("mandat");
    }

    /**
     * Setter for the roles as a list (role menu in the dialog). Updates the set in the entity.
     *
     * <p>SECURITY (forensics 23.08.2026, K2): this method used to REPLACE the whole role set with the
     * form list. Since {@link #getSelectedRolesList()} hides the {@code PROPERTY_*} and tenant roles,
     * those never came back — every save silently deleted them along the way. And an
     * incompletely submitted selection (with a fixed 450 px on a phone viewport: an
     * empty one) meant the total loss of all roles. Now the roles hidden in the dialog are
     * preserved and the form list is only merged in; the loss of the VISIBLE roles is
     * decided by {@link #pruefeRollenEntzug(FacesContext, Set)}.</p>
     *
     * @param rolesList the (visible) roles selected in the dialog, may be {@code null}
     */
    public void setSelectedRolesList(List<String> rolesList) {
        if (selected == null) {
            return;
        }
        // Preserve the tenant role
        String currentMandat = selected.getMandat();

        // Preserve the roles hidden in the dialog, take the visible ones from the form list.
        Set<String> neueRollen = selected.getRoles() == null
                ? new HashSet<>()
                : selected.getRoles().stream()
                        .filter(MyUserBackingBean::istImDialogAusgeblendet)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(HashSet::new));
        if (rolesList != null) {
            rolesList.stream()
                    .filter(role -> role != null && !role.trim().isEmpty())
                    .forEach(neueRollen::add);
        }
        selected.setRoles(neueRollen);

        // Add the tenant role back, if there is one
        if (currentMandat != null && !currentMandat.isEmpty()) {
            selected.setMandat(currentMandat);
        }
    }

    /**
     * Synchronizes the roles from the list (UI) back to the set (entity).
     * Is called before saving.
     */
    private void syncRolesFromListToSet() {
        if (selected == null) {
            return;
        }
        // The setSelectedRolesList method already performs the synchronization
        setSelectedRolesList(getSelectedRolesList());
    }

    /**
     * Returns all available mandates from the security system.
     * @return List of all mandate names
     */
    public List<String> getAllMandate() {
        List<String> mandateList = new ArrayList<>();
        try {
            if (plaintextSecurity != null) {
                Set<String> allMandate = plaintextSecurity.getAllMandate();
                if (allMandate != null && !allMandate.isEmpty()) {
                    mandateList.addAll(allMandate);
                }
            }
        } catch (Exception e) {
            log.warn("Could not load mandates from security system: {}", e.getMessage());
        }

        // Sort alphabetically
        mandateList.sort(String::compareTo);
        return mandateList;
    }

    /**
     * Starts impersonation of the selected user.
     * Only available for root users.
     */
    public void impersonateUser(MyUserEntity user) {
        if (!isRoot()) {
            log.warn("SECURITY: Non-root user attempted to impersonate user {}", user != null ? user.getId() : "null");
            FacesMessages.error("Fehler", "Keine Berechtigung für diese Aktion.");
            return;
        }

        if (user == null || user.getId() == null) {
            log.warn("Cannot impersonate - user is null or has no ID");
            FacesMessages.error("Fehler", "Ungültiger Benutzer.");
            return;
        }

        // Check if user is trying to impersonate themselves
        Long currentUserId = plaintextSecurity.getId();
        if (user.getId().equals(currentUserId)) {
            log.warn("User {} attempted to impersonate themselves", currentUserId);
            FacesMessages.warn("Warnung", "Sie können sich nicht selbst impersonieren.");
            return;
        }

        try {
            plaintextSecurity.startImpersonation(user.getId());
            log.info("Root user started impersonation of user {} ({})", user.getId(), Log.mail(user.getUsername()));

            FacesMessages.info("Erfolg", "Sie agieren jetzt als Benutzer: " + user.getUsername());

            // Reload page to reflect new security context
            FacesContext.getCurrentInstance().getExternalContext().redirect("index.xhtml");
        } catch (Exception e) {
            log.error("Error starting impersonation for user {}", user.getId(), e);
            FacesMessages.error("Fehler", "Impersonation konnte nicht gestartet werden: " + e.getMessage());
        }
    }

    /**
     * Loads the additional tenants of the currently selected user into the dialog selection.
     */
    private void loadZusatzMandate() {
        selectedZusatzMandate = new ArrayList<>();
        if (selected != null && selected.getUsername() != null) {
            for (UserMandate um : userMandateRepo.findByUsername(selected.getUsername())) {
                selectedZusatzMandate.add(um.getMandat());
            }
        }
    }

    /**
     * Persists the additional tenants selected in the dialog (ROOT only). The home tenant
     * of the user is not stored as an additional one (duplicate).
     */
    @Transactional
    public void saveZusatzMandate() {
        if (!isRoot() || selected == null || selected.getUsername() == null) {
            return;
        }
        String username = selected.getUsername();
        userMandateRepo.deleteByUsername(username);
        // Flush the deletions to the DB IMMEDIATELY, BEFORE the re-inserts below. deleteByUsername is a
        // Spring Data derived delete (SELECT + em.remove) → the deletes are only pending and would
        // otherwise be executed at commit time. Hibernate flushes INSERTs BEFORE DELETEs in that case → a re-inserted
        // (username, mandat) whose old row still exists violates the unique index uq_user_mandate
        // (DataIntegrityViolationException, e.g. simon+guild42). The explicit flush enforces the right
        // order (first remove all old rows, then insert the deduplicated form list).
        userMandateRepo.flush();
        String home = selected.getMandat() != null ? selected.getMandat().toLowerCase() : null;
        Set<String> seen = new HashSet<>();
        if (selectedZusatzMandate != null) {
            for (String m : selectedZusatzMandate) {
                if (m == null || m.trim().isEmpty()) {
                    continue;
                }
                String ml = m.toLowerCase();
                if (ml.equals(home) || !seen.add(ml)) {
                    continue;
                }
                UserMandate um = new UserMandate();
                um.setUsername(username);
                um.setMandat(ml);
                um.setActive(true);
                userMandateRepo.save(um);
            }
        }
        log.info("Zusatz-Mandate für {} gespeichert: {}", Log.mail(username), seen);
    }

    /**
     * Stops the current impersonation and returns to original user.
     */
    public void stopImpersonation() {
        try {
            plaintextSecurity.stopImpersonation();
            log.info("Stopped impersonation");

            FacesMessages.info("Erfolg", "Impersonation beendet - Sie sind wieder als Ihr ursprünglicher Benutzer angemeldet.");

            // Reload page to reflect restored security context
            FacesContext.getCurrentInstance().getExternalContext().redirect("index.xhtml");
        } catch (Exception e) {
            log.error("Error stopping impersonation", e);
            FacesMessages.error("Fehler", "Impersonation konnte nicht beendet werden: " + e.getMessage());
        }
    }

}
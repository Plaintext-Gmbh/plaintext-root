/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.audit.DestructiveActionAuditService;
import ch.plaintext.boot.plugins.security.magiclink.MagicLinkService;
import ch.plaintext.boot.plugins.security.model.MyRememberMe;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.model.UserMandate;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import ch.plaintext.framework.PlaintextRole;
import ch.plaintext.framework.PlaintextRoleRegistry;
import ch.plaintext.framework.PrivilegedRoleRules;
import jakarta.annotation.PostConstruct;
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

    /** Spaltenbreite von {@code destructive_action_audit.detail}; laengere Texte werden gekuerzt. */
    private static final int AUDIT_DETAIL_MAX = 2000;

    /**
     * SECURITY (Karte 314, Punkt 7): zentrale {@link PasswordEncoder}-Bean statt eines lokalen
     * {@code new BCryptPasswordEncoder()}. Der lokale Aufruf haette den Spring-Default-Kostenfaktor
     * 10 behalten, waehrend die Bean in {@code PlaintextSecurityConfig} auf 12 steht — die
     * Kostenfaktoren waeren also je nach Codepfad auseinandergedriftet.
     */
    private transient PasswordEncoder passwordEncoder;
    private boolean remlistcolapsed = false;
    private MyUserEntity selected;
    private String myUserPw;
    private MyRememberMe selectedRememberMe;

    private MyUserRepository repo;

    /**
     * Rollen-Registry (Karte: Modul-Rollen-Registrierung): liefert die von den Modulen
     * deklarierten Rollen fuer die Auswahl im Benutzer-Dialog. Optional verdrahtet, damit
     * Kontexte ohne Registry (z.B. schlanke Tests) weiter funktionieren.
     */
    private transient PlaintextRoleRegistry roleRegistry;

    private MyRememberMeRepository rememberMeRepo;

    private PlaintextSecurity plaintextSecurity;

    private transient UserMandateRepository userMandateRepo;

    private transient MagicLinkService magicLinkService;

    /**
     * SECURITY (Forensik 23.08.2026): Audit-Schreiber fuer Rollenaenderungen und Benutzerloeschungen. Wie
     * {@link #roleRegistry} optional verdrahtet, damit schlanke Kontexte (Tests ohne JPA) die Bean
     * weiter bauen koennen — fehlt er, bleibt nur das {@code log.warn} als Spur.
     */
    private transient DestructiveActionAuditService auditService;

    /**
     * SECURITY (Forensik 23.08.2026): Die Rueckfrage, die beim Entzug privilegierter Rollen durch einen
     * root-Akteur angezeigt wird. {@code null} = keine Rueckfrage offen.
     */
    private String rollenEntzugFrage;

    /**
     * SECURITY (Forensik 23.08.2026): {@code true}, sobald der root-Akteur den Entzug im Dialog bestaetigt hat.
     * Wird nach jedem abgeschlossenen Vorgang (Speichern, Abbruch, Auswahlwechsel) zurueckgesetzt,
     * damit eine Bestaetigung nie fuer einen zweiten, ungesehenen Entzug weitergilt.
     */
    private boolean rollenEntzugBestaetigt;

    /**
     * Konstruktor-Injection statt Feld-Injection (Sonar S6813): die Abhaengigkeiten stehen damit
     * schon vor {@code @PostConstruct} fest und die Bean laesst sich ohne Spring bauen.
     *
     * @param passwordEncoder zentrale Encoder-Bean (Kostenfaktor 12)
     * @param repo            Benutzer-Repository
     * @param roleRegistry    Rollen-Registry; optional, darf {@code null} sein
     * @param rememberMeRepo  Remember-Me-Repository
     * @param plaintextSecurity Sicherheitskontext (Rollen, Mandat)
     * @param userMandateRepo Repository der Zusatz-Mandate
     * @param magicLinkService Versand der Magic-Links
     * @param auditService    Audit-Log fuer Rollenaenderungen/Loeschungen; optional, darf {@code null} sein
     */
    @Autowired
    public MyUserBackingBean(PasswordEncoder passwordEncoder,
                             MyUserRepository repo,
                             @Nullable PlaintextRoleRegistry roleRegistry,
                             MyRememberMeRepository rememberMeRepo,
                             PlaintextSecurity plaintextSecurity,
                             UserMandateRepository userMandateRepo,
                             MagicLinkService magicLinkService,
                             @Nullable DestructiveActionAuditService auditService) {
        this.passwordEncoder = passwordEncoder;
        this.repo = repo;
        this.roleRegistry = roleRegistry;
        this.rememberMeRepo = rememberMeRepo;
        this.plaintextSecurity = plaintextSecurity;
        this.userMandateRepo = userMandateRepo;
        this.magicLinkService = magicLinkService;
        this.auditService = auditService;
    }

    /** Zusätzliche Mandate des gewählten Benutzers (Mehrfach-Mandant), im Dialog editierbar. */
    private List<String> selectedZusatzMandate = new ArrayList<>();

    private List<MyUserEntity> users = new ArrayList<>();
    private List<MyRememberMe> rememberMes = new ArrayList<>();

    @PostConstruct
    public void init() {
        log.info("Loading users for user administration");

        users.clear();

        // Root sieht alle Benutzer, Admin nur die des eigenen Mandats
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
     * Prüft ob der aktuelle Benutzer die Root-Rolle hat.
     */
    public boolean isRoot() {
        return plaintextSecurity != null && plaintextSecurity.ifGranted("ROLE_root");
    }

    /**
     * Prüft ob der aktuelle Benutzer die Admin-Rolle hat.
     */
    public boolean isAdmin() {
        return plaintextSecurity != null && plaintextSecurity.ifGranted("ROLE_admin");
    }

    /**
     * Prüft ob der aktuelle Benutzer Zugriff auf die Benutzerverwaltung hat.
     * Wird beim preRenderView aufgerufen.
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
     * Oeffnet den Dialog fuer einen neuen Benutzer.
     *
     * <p>DATENQUALITAET (Forensik 23.08.2026): Die leere Entity wird <b>nicht mehr sofort persistiert</b>.
     * Vorher legte jeder Klick auf „Neuer Benutzer" — auch ein abgebrochener — eine Waisenzeile
     * mit leerem {@code username} an, die von der Benutzerverwaltung nie wieder eingesammelt wurde.
     * Die Entity bleibt jetzt transient, bis {@link #save()} sie mit gueltigem Benutzernamen und
     * Passwort tatsaechlich schreibt; {@code save()} kommt mit {@code id == null} zurecht
     * (Insert statt Update). Auch das {@code init()} entfaellt: Es gibt nichts nachzuladen.</p>
     */
    public void newUser() {
        selected = new MyUserEntity();
        // Set default mandate for new user (can be changed in the dialog)
        selected.setMandat("default");
        select();
    }

    public void select() {
        log.debug("SELECT called - selected: {}", selected != null ? selected.getId() + "/" + selected.getUsername() : "null");
        resetRollenEntzug();
        if (selected != null) {
            myUserPw = selected.getPassword();
            loadZusatzMandate();
        }
    }

    /**
     * Oeffnet den Bearbeiten-Dialog fuer die uebergebene Zeile (Zeilen-Button in der
     * Benutzer-Liste): setzt die Auswahl und laedt die Dialog-Daten wie {@link #select()}.
     *
     * @param user der Benutzer aus der angeklickten Tabellenzeile
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

        // Prüfe ob Username bereits existiert (nur bei neuen Benutzern oder bei Änderung)
        MyUserEntity existingUser = repo.findByUsername(selected.getUsername());
        if (existingUser != null && !existingUser.getId().equals(selected.getId())) {
            FacesContext.getCurrentInstance().addMessage("username",
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Ein Benutzer mit dieser E-Mail-Adresse existiert bereits."));
        }
    }

    @Transactional
    public void save() {
        FacesContext context = FacesContext.getCurrentInstance();

        // Validiere E-Mail-Format
        if (selected.getUsername() == null || selected.getUsername().trim().isEmpty()) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Benutzername darf nicht leer sein."));
            context.validationFailed();
            return;
        }

        if (!EMAIL_PATTERN.matcher(selected.getUsername()).matches()) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Benutzername muss eine gültige E-Mail-Adresse sein."));
            context.validationFailed();
            return;
        }

        // Prüfe ob Username bereits existiert (nur bei neuen Benutzern oder bei Änderung)
        MyUserEntity existingUser = repo.findByUsername(selected.getUsername());
        if (existingUser != null && !existingUser.getId().equals(selected.getId())) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Ein Benutzer mit dieser E-Mail-Adresse existiert bereits."));
            context.validationFailed();
            return;
        }

        // SECURITY (Karte 307, K1): Snapshot der PERSISTIERTEN Rollen (frisch per ID aus der DB, nicht das
        // evtl. schon vom JSF-Model-Update ueberschriebene `selected`) — Basis fuer die Rollen-Allowlist,
        // damit Bestand editierbar bleibt, ein Nicht-ROOT aber keine privilegierte Rolle NEU vergeben kann.
        Set<String> persistedRoles = new HashSet<>();
        if (selected.getId() != null) {
            MyUserEntity persisted = repo.findById(selected.getId()).orElse(null);
            if (persisted != null && persisted.getRoles() != null) {
                persistedRoles = new HashSet<>(persisted.getRoles());
            }
        }

        // Validiere Passwort bei neuen Benutzern oder wenn Passwort geändert wurde
        boolean isNewUser = myUserPw == null || myUserPw.isEmpty();
        boolean passwordChanged = !selected.getPassword().isEmpty() && !selected.getPassword().equals(myUserPw);

        if (!selected.isPasswordless() && isNewUser && selected.getPassword().isEmpty()) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Passwort darf bei einem neuen Benutzer nicht leer sein."));
            context.validationFailed();
            return;
        }

        if (selected.isPasswordless()) {
            selected.setPassword("");
        }

        // Synchronize roles from chips component (List) back to entity (Set)
        syncRolesFromListToSet();

        // SECURITY (Karte 307, K1): serverseitige Rollen-Allowlist. Ein Nicht-ROOT-Akteur darf ueber die
        // (fuer ihn legitime) Benutzerverwaltung KEINE privilegierten Rollen NEU vergeben. Welche Rollen
        // das sind, entscheidet zentral PrivilegedRoleRules: "root"/"admin" (Verwaltungsrechte, die sich
        // sonst selbst weiterreichen liessen) und jede "PROPERTY_*"-Rolle (Mandat-Wechsel/Cross-Mandant).
        // Modul-Rollen sind ausdruecklich NICHT privilegiert — sie zu vergeben ist admins Aufgabe.
        // Bereits am persistierten Datensatz vorhandene Rollen bleiben erlaubt (Bestand editierbar). Der
        // Serverseiten-Check ist der eigentliche Fix; die UI-Freitext-Chips sind nur Bequemlichkeit.
        if (!isRoot() && !pruefeRollenAllowlist(context, persistedRoles)) {
            return;
        }

        // SECURITY (Forensik 23.08.2026, K1): die ENTZUGSSEITE. pruefeRollenAllowlist() sieht nur, was
        // uebermittelt wurde — was FEHLT, sah bisher niemand. Genau so verlor ein Administratorkonto
        // still 'root' und 'admin', als ein root-Akteur vom Telefon aus mit leerer Rollenauswahl
        // speicherte. Die Differenz `persistiert \ uebermittelt` ist der einzige Ort, an dem ein
        // solcher Verlust ueberhaupt sichtbar wird.
        Set<String> neueRollen = selected.getRoles() == null
                ? new HashSet<>() : new HashSet<>(selected.getRoles());
        Set<String> entzogenePrivilegierte = entzogenePrivilegierteRollen(persistedRoles, neueRollen);
        if (!entzogenePrivilegierte.isEmpty() && !pruefeRollenEntzug(context, entzogenePrivilegierte)) {
            return;
        }

        // Set default mandate if none is specified
        if (selected.getMandat() == null || selected.getMandat().trim().isEmpty()) {
            selected.setMandat("default");
            log.debug("No mandate specified for user {}, setting to 'default'", selected.getUsername());
        }

        if (!selected.getPassword().isEmpty() && !selected.getPassword().startsWith("$2a$10")) {
            selected.setPassword(passwordEncoder.encode(selected.getPassword()));
        } else {
            selected.setPassword(myUserPw);
        }
        repo.save(selected);
        // NACHVOLLZIEHBARKEIT (Forensik 23.08.2026): erst nach dem erfolgreichen Schreiben protokollieren —
        // sonst behauptet das Log eine Aenderung, die es womoeglich nie gab.
        protokolliereRollenaenderung(persistedRoles, selected.getRoles());
        saveZusatzMandate();
        selected = null;
        resetRollenEntzug();
        init();
        context.addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Benutzer erfolgreich gespeichert."));
    }

    /**
     * Prueft die am Formular gewaehlten Rollen gegen {@link PrivilegedRoleRules}. Bereits
     * persistierte Rollen sind immer erlaubt — nur die NEUE Vergabe ist eingeschraenkt.
     *
     * @param context        FacesContext fuer die Fehlermeldung
     * @param persistedRoles die Rollen des persistierten Datensatzes (Bestand)
     * @return {@code true}, wenn gespeichert werden darf
     */
    private boolean pruefeRollenAllowlist(FacesContext context, Set<String> persistedRoles) {
        for (String role : selected.getRoles()) {
            if (role == null || !PrivilegedRoleRules.isPrivileged(role) || persistedRoles.contains(role)) {
                continue;
            }
            log.warn("SECURITY (Karte 307, K1): Nicht-ROOT-Akteur versuchte, privilegierte Rolle '{}' "
                    + "an Benutzer '{}' zu vergeben — abgelehnt.", role, selected.getUsername());
            context.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                    PrivilegedRoleRules.rejectionMessage(role)));
            context.validationFailed();
            return false;
        }
        return true;
    }

    /**
     * Welche der persistierten Rollen dem Benutzer durch diese Speicherung ENTZOGEN wuerden und
     * dabei privilegiert im Sinne von {@link PrivilegedRoleRules} sind.
     *
     * <p>Mandat-Rollen ({@code PROPERTY_MANDAT_*}) bleiben aussen vor: Der Mandant ist ein eigenes,
     * sichtbares Dialogfeld mit eigener Semantik — ein Mandatswechsel ist kein stiller
     * Rechteverlust, und eine Rueckfrage bei jedem Mandatswechsel wuerde die Warnung entwerten.</p>
     *
     * @param persistiert Rollen des persistierten Datensatzes
     * @param neu         Rollen nach der Formularuebernahme
     * @return die entzogenen privilegierten Rollen, sortiert; nie {@code null}
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
     * Der Entzugs-Schutz. Ein Nicht-root-Akteur darf privilegierte Rollen ueberhaupt nicht
     * entziehen; ein root-Akteur darf es, muss es aber ausdruecklich bestaetigen. Der Vorfall, der
     * zu dieser Karte fuehrte, war genau ein root-Akteur — ein reiner Nicht-root-Schutz haette ihn
     * nicht verhindert.
     *
     * @param context  FacesContext fuer die Meldung
     * @param entzogen die entzogenen privilegierten Rollen (nicht leer)
     * @return {@code true}, wenn gespeichert werden darf
     */
    private boolean pruefeRollenEntzug(FacesContext context, Set<String> entzogen) {
        String rollen = String.join(", ", entzogen);
        String benutzer = selected.getUsername();
        if (!isRoot()) {
            log.warn("SECURITY (Rollen-Entzug): Nicht-ROOT-Akteur '{}' versuchte, dem Benutzer '{}' die "
                    + "privilegierte(n) Rolle(n) {} zu ENTZIEHEN — abgelehnt, nichts gespeichert.",
                    handelnderBenutzer(), benutzer, rollen);
            context.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                    "Nur ROOT darf die Rolle(n) " + rollen + " entziehen. Die Änderung wurde NICHT "
                            + "gespeichert."));
            context.validationFailed();
            resetRollenEntzug();
            return false;
        }
        if (!rollenEntzugBestaetigt) {
            rollenEntzugFrage = "Dem Benutzer " + benutzer + " werden die Rollen " + rollen
                    + " entzogen — fortfahren?";
            log.warn("SECURITY (Rollen-Entzug): ROOT-Akteur '{}' entzieht dem Benutzer '{}' die "
                    + "privilegierte(n) Rolle(n) {} — Bestaetigung angefordert, noch NICHT gespeichert.",
                    handelnderBenutzer(), benutzer, rollen);
            context.validationFailed();
            return false;
        }
        return true;
    }

    /**
     * Der root-Akteur hat den Rollen-Entzug im Bestaetigungsdialog bejaht: gleicher
     * {@link #save()}-Pfad, diesmal mit gesetztem Bestaetigungs-Flag.
     */
    public void bestaetigeRollenEntzugUndSpeichere() {
        rollenEntzugBestaetigt = true;
        rollenEntzugFrage = null;
        save();
    }

    /** Der root-Akteur hat den Rollen-Entzug abgelehnt: nichts speichern, Rueckfrage schliessen. */
    public void brichRollenEntzugAb() {
        log.warn("SECURITY (Rollen-Entzug): ROOT-Akteur '{}' hat den Rollen-Entzug abgebrochen — "
                + "nichts gespeichert.", handelnderBenutzer());
        resetRollenEntzug();
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO,
                "Abgebrochen", "Der Rollen-Entzug wurde nicht gespeichert."));
    }

    /** Ob gerade eine unbeantwortete Rueckfrage zum Rollen-Entzug offen ist (fuer die Oberflaeche). */
    public boolean isRollenEntzugAusstehend() {
        return rollenEntzugFrage != null;
    }

    private void resetRollenEntzug() {
        rollenEntzugFrage = null;
        rollenEntzugBestaetigt = false;
    }

    /**
     * Protokolliert eine Rollenaenderung als {@code WARN} (vorher → nachher, handelnder Benutzer)
     * und schreibt sie zusaetzlich ins {@code destructive_action_audit}. Ohne Aenderung passiert
     * nichts — sonst ertraenkt jedes Speichern eines Startseiten-Felds das Log.
     *
     * @param vorher  Rollen des persistierten Datensatzes vor der Speicherung
     * @param nachher Rollen nach der Speicherung
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
                selected.getUsername(), selected.getId(), alt, neu, hinzugefuegt, entzogen,
                handelnderBenutzer());

        if (auditService != null) {
            auditService.logDestructiveAction("UI", "USER_ROLES_CHANGED", "MyUserEntity",
                    String.valueOf(selected.getId()),
                    kuerze("Benutzer '" + selected.getUsername() + "': vorher " + alt + " → nachher "
                            + neu + "; entzogen: " + entzogen + "; vergeben: " + hinzugefuegt
                            + "; durch: " + handelnderBenutzer()));
        }
    }

    /**
     * Loescht den gewaehlten Benutzer.
     *
     * <p>NACHVOLLZIEHBARKEIT (Forensik 23.08.2026): Die Loeschung lief bisher komplett auf {@code log.debug} —
     * in Produktion (Level INFO) war sie damit unsichtbar und im Audit gar nicht vorhanden.
     * Jetzt: {@code WARN} mit Benutzer, ID, Mandat, Rollen und handelndem Benutzer, plus ein
     * Eintrag im {@code destructive_action_audit}.</p>
     */
    public void delete() {
        if (selected == null || selected.getId() == null) {
            log.warn("AUDIT Benutzerloeschung: durch '{}' angefordert, aber kein gespeicherter "
                    + "Benutzer ausgewaehlt — nichts geloescht.", handelnderBenutzer());
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
                    username, id, mandat, rollen, handelnderBenutzer());
            if (auditService != null) {
                auditService.logDestructiveAction("UI", "USER_DELETE", "MyUserEntity",
                        String.valueOf(id),
                        kuerze("Benutzer '" + username + "' (Mandat " + mandat + ") geloescht; Rollen "
                                + rollen + "; durch: " + handelnderBenutzer()));
            }
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Benutzer erfolgreich gelöscht."));
        } catch (Exception e) {
            log.error("AUDIT Benutzerloeschung FEHLGESCHLAGEN: '{}' (id={}) durch '{}'",
                    username, id, handelnderBenutzer(), e);
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Fehler beim Löschen des Benutzers: " + e.getMessage()));
        }

        selected = null;
        resetRollenEntzug();
        init();
    }

    /** Der angemeldete (bzw. impersonierende) Benutzer, der die Aenderung ausloest. */
    private String handelnderBenutzer() {
        if (plaintextSecurity == null) {
            return "unbekannt";
        }
        String user = plaintextSecurity.getUser();
        return user == null || user.isBlank() ? "unbekannt" : user;
    }

    /** Kuerzt einen Audit-Freitext auf die Spaltenbreite von {@code detail} (VARCHAR(2000)). */
    private static String kuerze(String text) {
        if (text == null || text.length() <= AUDIT_DETAIL_MAX) {
            return text;
        }
        return text.substring(0, AUDIT_DETAIL_MAX - 3) + "...";
    }

    /** Ob die Rolle den Heimat-Mandanten kodiert ({@code PROPERTY_MANDAT_*}). */
    private static boolean istMandatRolle(String role) {
        return role != null && role.toUpperCase(Locale.ROOT).startsWith("PROPERTY_MANDAT_");
    }

    public void onToggle() {
        this.remlistcolapsed = !this.remlistcolapsed;
    }

    /**
     * Generiert und versendet einen Magic-Link an die E-Mail-Adresse des gewählten Benutzers.
     */
    public void sendMagicLink() {
        FacesContext context = FacesContext.getCurrentInstance();
        String username = selected.getUsername();
        HttpServletRequest request = (HttpServletRequest) context.getExternalContext().getRequest();
        boolean sent = magicLinkService.generateAndSend(username, request);
        if (sent) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Magic-Link an " + username + " gesendet."));
        } else {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Magic-Link konnte nicht gesendet werden."));
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
     * Alle in der Benutzerverwaltung anbietbaren Rollen (lowercase, ohne ROLE_ Präfix):
     * die Union aus den von den Modulen DEKLARIERTEN Rollen ({@link PlaintextRoleRegistry},
     * Rollen-Registry-Muster analog zum Menü-System) und den bereits in der Datenbank
     * VERGEBENEN Rollen (Bestand — Rollen, die kein Modul mehr deklariert, bleiben so
     * sichtbar und gehen nicht verloren).
     * Properties (PROPERTY_*) und Mandat-Rollen werden herausgefiltert.
     *
     * <p>Ersetzt den früheren Laufzeit-Scan der XHTML-Dateien nach ifGranted-Patterns, der
     * nur im Dev-Checkout funktionierte (las {@code src/main/resources} vom Dateisystem).</p>
     *
     * @return Set von eindeutigen Rollennamen (lowercase, ohne ROLE_ Präfix)
     */
    public Set<String> getAvailableRoles() {
        Set<String> roles = new LinkedHashSet<>();

        // 1. Von Modulen deklarierte Rollen (Registry)
        roles.addAll(extractRolesFromRegistry());

        // 2. Rollen aus der Datenbank extrahieren (Bestand)
        roles.addAll(extractRolesFromDatabase());

        // 3. Filtere Properties und Mandat-Rollen heraus
        return roles.stream()
                .filter(role -> !role.toLowerCase().startsWith("property_"))
                .filter(role -> !role.toLowerCase().contains("mandat"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Die von den Modulen deklarierten Rollen (normalisiert: lowercase, ohne ROLE_ Präfix).
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
     * Die Auswahl-Einträge für das Rollen-SelectCheckboxMenu im Benutzer-Dialog:
     * {@link #getAvailableRoles()} plus die aktuell am Benutzer gesetzten Rollen (damit eine
     * vorselektierte, aber nirgends mehr deklarierte Rolle im Menü sichtbar bleibt).
     * Deklarierte Rollen tragen ihre Beschreibung im Label.
     *
     * @return sortierte Auswahl-Einträge
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
     * Ein Eintrag der Rollen-Auswahl im Benutzer-Dialog: technischer Wert plus Anzeige-Label
     * (Name, bei deklarierten Rollen inkl. Beschreibung aus der {@link PlaintextRole}).
     */
    @lombok.Value
    public static class RoleOption implements Serializable {
        String name;
        String label;
    }

    /**
     * Extrahiert alle verwendeten Rollen aus der Datenbank
     */
    private Set<String> extractRolesFromDatabase() {
        Set<String> roles = new LinkedHashSet<>();

        try {
            List<MyUserEntity> allUsers = repo.findAll();
            for (MyUserEntity user : allUsers) {
                if (user.getRoles() != null) {
                    for (String role : user.getRoles()) {
                        // Filtere "mandat" Rollen aus (siehe MyUserDetailsService)
                        if (!role.contains("mandat")) {
                            // Entferne ROLE_ Präfix falls vorhanden und konvertiere zu lowercase
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
     * Getter für die Rollen als Liste (für p:chips Komponente).
     * Konvertiert das Set in eine Liste und filtert Properties und Mandat-Rollen heraus.
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
     * Ob die Rolle im Rollen-Menue des Dialogs bewusst ausgeblendet wird: Properties
     * ({@code PROPERTY_*}) und Mandat-Rollen. Sie werden dort weder angezeigt noch uebermittelt —
     * und duerfen deshalb beim Speichern auch nicht als „abgewaehlt" gelten.
     *
     * @param role Rollenname, darf {@code null} sein
     * @return {@code true}, wenn die Rolle im Dialog nicht sichtbar ist
     */
    private static boolean istImDialogAusgeblendet(String role) {
        if (role == null) {
            return true;
        }
        return role.toUpperCase(Locale.ROOT).startsWith("PROPERTY_")
                || role.toLowerCase(Locale.ROOT).contains("mandat");
    }

    /**
     * Setter für die Rollen als Liste (Rollen-Menü im Dialog). Aktualisiert das Set im Entity.
     *
     * <p>SECURITY (Forensik 23.08.2026, K2): Frueher ERSETZTE diese Methode das gesamte Rollen-Set durch die
     * Formularliste. Da {@link #getSelectedRolesList()} die {@code PROPERTY_*}- und Mandat-Rollen
     * ausblendet, kamen diese nie zurueck — jedes Speichern loeschte sie still mit. Und eine
     * unvollstaendig uebermittelte Auswahl (bei fixen 450 px auf einem Telefon-Viewport: eine
     * leere) bedeutete Totalverlust aller Rollen. Jetzt werden die im Dialog ausgeblendeten Rollen
     * bewahrt und die Formularliste nur dazugemischt; ueber den Verlust der SICHTBAREN Rollen
     * entscheidet {@link #pruefeRollenEntzug(FacesContext, Set)}.</p>
     *
     * @param rolesList die im Dialog gewaehlten (sichtbaren) Rollen, darf {@code null} sein
     */
    public void setSelectedRolesList(List<String> rolesList) {
        if (selected == null) {
            return;
        }
        // Bewahre die Mandat-Rolle
        String currentMandat = selected.getMandat();

        // Im Dialog ausgeblendete Rollen bewahren, sichtbare aus der Formularliste uebernehmen.
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

        // Füge die Mandat-Rolle wieder hinzu, falls vorhanden
        if (currentMandat != null && !currentMandat.isEmpty()) {
            selected.setMandat(currentMandat);
        }
    }

    /**
     * Synchronisiert die Rollen von der Liste (UI) zurück zum Set (Entity).
     * Wird vor dem Speichern aufgerufen.
     */
    private void syncRolesFromListToSet() {
        if (selected == null) {
            return;
        }
        // Die setSelectedRolesList Methode macht bereits die Synchronisation
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
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Keine Berechtigung für diese Aktion."));
            return;
        }

        if (user == null || user.getId() == null) {
            log.warn("Cannot impersonate - user is null or has no ID");
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Ungültiger Benutzer."));
            return;
        }

        // Check if user is trying to impersonate themselves
        Long currentUserId = plaintextSecurity.getId();
        if (user.getId().equals(currentUserId)) {
            log.warn("User {} attempted to impersonate themselves", currentUserId);
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Sie können sich nicht selbst impersonieren."));
            return;
        }

        try {
            plaintextSecurity.startImpersonation(user.getId());
            log.info("Root user started impersonation of user {} ({})", user.getId(), user.getUsername());

            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg",
                            "Sie agieren jetzt als Benutzer: " + user.getUsername()));

            // Reload page to reflect new security context
            FacesContext.getCurrentInstance().getExternalContext().redirect("index.xhtml");
        } catch (Exception e) {
            log.error("Error starting impersonation for user {}", user.getId(), e);
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                            "Impersonation konnte nicht gestartet werden: " + e.getMessage()));
        }
    }

    /**
     * Lädt die zusätzlichen Mandate des aktuell gewählten Benutzers in die Dialog-Auswahl.
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
     * Persistiert die im Dialog gewählten zusätzlichen Mandate (nur ROOT). Der Heimat-Mandant
     * des Benutzers wird nicht als Zusatz gespeichert (Duplikat).
     */
    @Transactional
    public void saveZusatzMandate() {
        if (!isRoot() || selected == null || selected.getUsername() == null) {
            return;
        }
        String username = selected.getUsername();
        userMandateRepo.deleteByUsername(username);
        // Löschungen SOFORT in die DB flushen, BEVOR unten neu eingefügt wird. deleteByUsername ist ein
        // Spring-Data-Derived-Delete (SELECT + em.remove) → die Deletes sind nur vorgemerkt und würden
        // sonst erst beim Commit ausgeführt. Hibernate flusht dabei INSERTs VOR DELETEs → ein re-inserter
        // (username, mandat), der noch als alte Zeile existiert, verletzt den Unique-Index uq_user_mandate
        // (DataIntegrityViolationException, z. B. simon+guild42). Der explizite flush erzwingt die richtige
        // Reihenfolge (erst alle alten Zeilen weg, dann die deduplizierte Formular-Liste einfügen).
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
        log.info("Zusatz-Mandate für {} gespeichert: {}", username, seen);
    }

    /**
     * Stops the current impersonation and returns to original user.
     */
    public void stopImpersonation() {
        try {
            plaintextSecurity.stopImpersonation();
            log.info("Stopped impersonation");

            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg",
                            "Impersonation beendet - Sie sind wieder als Ihr ursprünglicher Benutzer angemeldet."));

            // Reload page to reflect restored security context
            FacesContext.getCurrentInstance().getExternalContext().redirect("index.xhtml");
        } catch (Exception e) {
            log.error("Error stopping impersonation", e);
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                            "Impersonation konnte nicht beendet werden: " + e.getMessage()));
        }
    }

}
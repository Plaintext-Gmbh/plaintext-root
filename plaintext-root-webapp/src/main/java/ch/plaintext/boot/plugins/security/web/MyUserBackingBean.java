/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.magiclink.MagicLinkService;
import ch.plaintext.boot.plugins.security.model.MyRememberMe;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.model.UserMandate;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Scope("session")
@Component
@Data
public class MyUserBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    /**
     * SECURITY (Karte 314, Punkt 7): zentrale {@link PasswordEncoder}-Bean statt eines lokalen
     * {@code new BCryptPasswordEncoder()}. Der lokale Aufruf haette den Spring-Default-Kostenfaktor
     * 10 behalten, waehrend die Bean in {@code PlaintextSecurityConfig} auf 12 steht — die
     * Kostenfaktoren waeren also je nach Codepfad auseinandergedriftet.
     */
    @Autowired
    private transient PasswordEncoder passwordEncoder;
    private boolean remlistcolapsed = false;
    private MyUserEntity selected;
    private String myUserPw;
    private MyRememberMe selectedRememberMe;
    private List<String> tempRoles = new ArrayList<>();
    private List<String> availableRolesList = new ArrayList<>();

    @Autowired
    private MyUserRepository repo;

    @Autowired
    private MyRememberMeRepository rememberMeRepo;

    @Autowired
    private PlaintextSecurity plaintextSecurity;

    @Autowired
    private UserMandateRepository userMandateRepo;

    @Autowired
    private MagicLinkService magicLinkService;

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

    public void newUser() {
        selected = new MyUserEntity();
        // Set default mandate for new user (can be changed in the dialog)
        selected.setMandat("default");
        selected = repo.save(selected);
        select();
        init();
    }

    public void select() {
        log.debug("SELECT called - selected: {}", selected != null ? selected.getId() + "/" + selected.getUsername() : "null");
        if (selected != null) {
            myUserPw = selected.getPassword();
            tempRoles.clear();
            updateAvailableRolesList();
            loadZusatzMandate();
        }
    }

    public void clearSelection() {
        log.debug("CLEAR SELECTION called");
        selected = null;
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
        // (fuer ihn legitime) Benutzerverwaltung KEINE privilegierten Rollen NEU vergeben — privilegiert =
        // "root" (jede Schreibweise) ODER jede "PROPERTY_*"-Rolle (z.B. Mandat-Wechsel/Cross-Mandant).
        // Bereits am persistierten Datensatz vorhandene Rollen bleiben erlaubt (Bestand editierbar). Der
        // Serverseiten-Check ist der eigentliche Fix; die UI-Freitext-Chips sind nur Bequemlichkeit.
        if (!isRoot()) {
            for (String role : selected.getRoles()) {
                if (role == null) {
                    continue;
                }
                boolean privileged = role.equalsIgnoreCase("root") || role.toUpperCase().startsWith("PROPERTY_");
                if (privileged && !persistedRoles.contains(role)) {
                    log.warn("SECURITY (Karte 307, K1): Nicht-ROOT-Akteur versuchte, privilegierte Rolle '{}' "
                            + "an Benutzer '{}' zu vergeben — abgelehnt.", role, selected.getUsername());
                    context.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                            "Nur ROOT darf die Rolle '" + role + "' vergeben."));
                    context.validationFailed();
                    return;
                }
            }
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
        saveZusatzMandate();
        selected = null;
        init();
        context.addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Benutzer erfolgreich gespeichert."));
    }

    public void delete() {
        log.debug("DELETE called - selected: {}", selected != null ? selected.getId() + "/" + selected.getUsername() : "null");
        if (selected != null && selected.getId() != null) {
            try {
                log.debug("Deleting user: {} with id: {}", selected.getUsername(), selected.getId());
                repo.delete(selected);
                log.debug("User deleted successfully");
                FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Benutzer erfolgreich gelöscht."));
            } catch (Exception e) {
                log.error("Error deleting user", e);
                FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Fehler beim Löschen des Benutzers: " + e.getMessage()));
            }
        } else {
            log.debug("DELETE called but selected is null or has no ID");
        }

        selected = null;
        init();
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
     * Scannt alle XHTML-Dateien nach p:ifGranted('ROLE_*') Patterns und extrahiert die Rollen.
     * Zusätzlich werden alle Rollen aus der Datenbank geholt.
     * Rollen werden in lowercase zurückgegeben und das 'ROLE_' Präfix wird entfernt.
     * Properties (PROPERTY_*) und Mandat-Rollen werden herausgefiltert.
     *
     * @return Set von eindeutigen Rollennamen (lowercase, ohne ROLE_ Präfix)
     */
    public Set<String> getAvailableRoles() {
        Set<String> roles = new LinkedHashSet<>();

        // 1. Rollen aus XHTML-Dateien extrahieren
        roles.addAll(extractRolesFromXhtmlFiles());

        // 2. Rollen aus der Datenbank extrahieren
        roles.addAll(extractRolesFromDatabase());

        // 3. Filtere Properties und Mandat-Rollen heraus
        return roles.stream()
                .filter(role -> !role.toLowerCase().startsWith("property_"))
                .filter(role -> !role.toLowerCase().contains("mandat"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Extrahiert Rollen aus allen XHTML-Dateien im Projekt
     */
    private Set<String> extractRolesFromXhtmlFiles() {
        Set<String> roles = new LinkedHashSet<>();

        // Pattern für p:ifGranted('ROLE_XXX') oder ähnliche Varianten
        Pattern rolePattern = Pattern.compile("(?:p:ifGranted|ifGranted|hasRole|hasAuthority)\\s*\\(\\s*['\"]ROLE_([A-Z_]+)['\"]\\s*\\)", Pattern.CASE_INSENSITIVE);

        try {
            // Finde das resources Verzeichnis
            String classPath = getClass().getClassLoader().getResource("").getPath();
            Path resourcesPath = Paths.get(classPath).getParent().getParent().resolve("src/main/resources");

            if (!Files.exists(resourcesPath)) {
                log.warn("Resources path does not exist: {}", resourcesPath);
                return roles;
            }

            // Durchsuche alle XHTML-Dateien
            try (Stream<Path> paths = Files.walk(resourcesPath)) {
                paths.filter(path -> path.toString().endsWith(".xhtml"))
                     .forEach(path -> {
                         try {
                             String content = Files.readString(path);
                             Matcher matcher = rolePattern.matcher(content);
                             while (matcher.find()) {
                                 String role = matcher.group(1).toLowerCase();
                                 roles.add(role);
                                 log.debug("Found role '{}' in file: {}", role, path.getFileName());
                             }
                         } catch (IOException e) {
                             log.error("Error reading file: {}", path, e);
                         }
                     });
            }
        } catch (Exception e) {
            log.error("Error scanning XHTML files for roles", e);
        }

        return roles;
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
                .filter(role -> !role.toUpperCase().startsWith("PROPERTY_"))
                .filter(role -> !role.toLowerCase().contains("mandat"))
                .collect(Collectors.toList());
    }

    /**
     * Setter für die Rollen als Liste (für p:chips Komponente).
     * Aktualisiert das Set im Entity.
     */
    public void setSelectedRolesList(List<String> rolesList) {
        if (selected == null) {
            return;
        }
        // Bewahre die Mandat-Rolle
        String currentMandat = selected.getMandat();

        // Erstelle ein neues Set mit den Rollen aus der Liste
        selected.setRoles(rolesList != null ? new HashSet<>(rolesList) : new HashSet<>());

        // Füge die Mandat-Rolle wieder hinzu, falls vorhanden
        if (currentMandat != null && !currentMandat.isEmpty()) {
            selected.setMandat(currentMandat);
        }
    }

    /**
     * Gibt verfügbare Rollen zurück, die noch nicht ausgewählt sind.
     */
    public Set<String> getAvailableRolesNotSelected() {
        Set<String> available = getAvailableRoles();
        Set<String> selected = getSelectedRolesList().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        return available.stream()
                .filter(role -> !selected.contains(role.toLowerCase()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Fügt die ausgewählten vorgeschlagenen Rollen zur Rollenliste hinzu.
     */
    public void addSuggestedRoles() {
        if (selected == null || tempRoles == null || tempRoles.isEmpty()) {
            return;
        }

        List<String> currentRoles = getSelectedRolesList();
        for (String role : tempRoles) {
            if (!currentRoles.contains(role)) {
                currentRoles.add(role);
            }
        }
        setSelectedRolesList(currentRoles);
        tempRoles.clear();
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
     * Aktualisiert die Liste der verfügbaren Rollen basierend auf den bereits ausgewählten Rollen.
     */
    private void updateAvailableRolesList() {
        if (selected == null) {
            availableRolesList.clear();
            return;
        }

        Set<String> allRoles = getAvailableRoles();
        Set<String> selectedRoles = getSelectedRolesList().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        availableRolesList = allRoles.stream()
                .filter(role -> !selectedRoles.contains(role.toLowerCase()))
                .collect(Collectors.toList());

        log.debug("Updated available roles list: {}", availableRolesList);
    }

    /**
     * Getter für die Liste der verfügbaren Rollen (für das zweite Chips-Element).
     */
    public List<String> getAvailableRolesList() {
        return availableRolesList;
    }

    /**
     * Setter für die Liste der verfügbaren Rollen.
     * Wird aufgerufen wenn Rollen im zweiten Chips-Element hinzugefügt/entfernt werden.
     */
    public void setAvailableRolesList(List<String> availableRolesList) {
        this.availableRolesList = availableRolesList;
    }

    /**
     * Event Handler wenn sich die ausgewählten Rollen ändern.
     * Aktualisiert die Liste der verfügbaren Rollen entsprechend.
     */
    public void onSelectedRolesChanged() {
        if (selected == null) {
            return;
        }

        // Finde Rollen die aus den ausgewählten Rollen entfernt wurden
        Set<String> allRoles = getAvailableRoles();
        List<String> currentSelected = getSelectedRolesList();
        List<String> previouslyAvailable = new ArrayList<>(availableRolesList);

        // Aktualisiere die verfügbaren Rollen basierend auf den ausgewählten
        updateAvailableRolesList();

        log.debug("Selected roles changed. Selected: {}, Available: {}", currentSelected, availableRolesList);
    }

    /**
     * Event Handler wenn sich die verfügbaren Rollen ändern.
     * Verschiebt gelöschte Rollen zu den ausgewählten Rollen.
     */
    public void onAvailableRolesChanged() {
        if (selected == null) {
            return;
        }

        // Speichere die aktuellen Listen
        Set<String> allPossibleRoles = getAvailableRoles();
        List<String> currentSelected = new ArrayList<>(getSelectedRolesList());
        List<String> currentAvailable = new ArrayList<>(availableRolesList);

        // Finde Rollen die in allPossibleRoles sind aber weder in currentSelected noch in currentAvailable
        // Diese wurden aus den verfügbaren Rollen gelöscht und sollten zu den ausgewählten hinzugefügt werden
        for (String role : allPossibleRoles) {
            boolean inSelected = currentSelected.stream().anyMatch(r -> r.equalsIgnoreCase(role));
            boolean inAvailable = currentAvailable.stream().anyMatch(r -> r.equalsIgnoreCase(role));

            if (!inSelected && !inAvailable) {
                // Diese Rolle wurde aus den verfügbaren gelöscht → füge zu ausgewählten hinzu
                currentSelected.add(role);
                log.debug("Moving role '{}' from available to selected", role);
            }
        }

        // Aktualisiere die ausgewählten Rollen
        setSelectedRolesList(currentSelected);

        // Aktualisiere die verfügbaren Rollen
        updateAvailableRolesList();

        log.debug("Available roles changed. Selected: {}, Available: {}", currentSelected, availableRolesList);
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
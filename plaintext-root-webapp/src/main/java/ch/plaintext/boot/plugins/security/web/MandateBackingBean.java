/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.model.UserMandate;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mandate Backing Bean
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Slf4j
@Scope("session")
@Component
@Named
@Data
public class MandateBackingBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    private PlaintextSecurity plaintextSecurity;

    @Autowired
    private MyUserRepository userRepository;

    @Autowired
    private transient MandateMenuConfigRepository mandateMenuConfigRepository;

    /** The second form of tenant membership: assigned additional tenants. */
    @Autowired
    private transient UserMandateRepository userMandateRepository;

    private List<String> mandate = new ArrayList<>();
    private List<MyUserEntity> users = new ArrayList<>();
    private List<MyUserEntity> filteredUsers; // For the table filter function
    private String selectedMandat;
    private String newMandatName;

    @PostConstruct
    public void init() {
        reload();
    }

    public void reload() {
        loadMandate();
        loadAllUsers();
    }

    private void loadMandate() {
        // Keep manually created tenants
        List<String> existingMandate = new ArrayList<>(mandate);

        mandate.clear();
        Set<String> allMandate = plaintextSecurity.getAllMandate();
        mandate.addAll(allMandate);

        // Add manually created tenants that are not in the DB yet
        for (String mandat : existingMandate) {
            if (!mandate.contains(mandat)) {
                mandate.add(mandat);
            }
        }

        if (!mandate.isEmpty() && selectedMandat == null) {
            selectedMandat = mandate.get(0);
        }

        log.debug("Loaded {} mandate", mandate.size());
    }

    private void loadAllUsers() {
        users.clear();
        users.addAll(userRepository.findAll());
        log.debug("Loaded {} users", users.size());
    }

    public void selectMandat() {
        log.debug("Selected mandat: {}", selectedMandat);
    }

    public void createMandat() {
        if (newMandatName == null || newMandatName.trim().isEmpty()) {
            FacesMessages.error("Fehler", "Mandatname darf nicht leer sein.");
            return;
        }

        String mandatKey = newMandatName.trim().toLowerCase();

        if (mandate.contains(mandatKey)) {
            FacesMessages.error("Fehler", "Mandat existiert bereits.");
            return;
        }

        try {
            // Check whether the tenant already exists in the database
            if (mandateMenuConfigRepository.existsByMandateName(mandatKey)) {
                FacesMessages.error("Fehler", "Mandat existiert bereits in der Datenbank.");
                return;
            }

            // Create a MandateMenuConfig entity and store it in the DB
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName(mandatKey);
            mandateMenuConfigRepository.save(config);
            log.info("Saved new mandat '{}' to database", mandatKey);

            newMandatName = "";

            // Reload the tenants to make sure that all sources are taken into account
            loadMandate();

            FacesMessages.info("Erfolg", "Mandat '" + mandatKey + "' erstellt.");

            log.debug("Created new mandat: {}", mandatKey);

        } catch (Exception e) {
            log.error("Error creating mandat", e);
            FacesMessages.error("Fehler", "Fehler beim Erstellen des Mandats: " + e.getMessage());
        }
    }

    public void saveUserMandat(MyUserEntity user) {
        try {
            userRepository.save(user);

            // If the currently logged-in user changes their own tenant, refresh the session
            if (plaintextSecurity.getId().equals(user.getId())) {
                // Reload the current user
                loadMandate();
            }

            FacesMessages.info("Gespeichert", "Mandat für Benutzer " + user.getUsername() + " aktualisiert.");

            log.debug("Updated mandat for user {} to {}", user.getUsername(), user.getMandat());
        } catch (Exception e) {
            log.error("Error saving user mandat", e);
            FacesMessages.error("Fehler", "Fehler beim Speichern: " + e.getMessage());
        }
    }

    /**
     * Removes a tenant from the <b>administration of the framework</b> — it disappears from the
     * selection list, because its menu configuration ({@code mandate_menu_config}) is deleted.
     * Business data of the application stays untouched.
     *
     * <p><b>Why not more.</b> Previously this method removed the tenant only from an
     * {@code ArrayList} in the session, without a single repository call, and still reported
     * "tenant deleted" — after the next {@link #reload()} it was back. A real
     * full deletion is something root cannot perform here at all: the business data of a tenant lies
     * in the applications (in plaintext-app spread over eight tables, for instance), and the framework
     * does not know these tables. Instead of feigning a full deletion, the action now does exactly
     * what root owns — and says so.</p>
     *
     * <p><b>Tenant membership comes in two forms</b>, and both are checked:
     * the home tenant in the role property {@code PROPERTY_MANDAT_<NAME>}
     * ({@code MyUserEntity.getMandat()}) and the additional tenants in the table
     * {@code user_mandate}. The earlier check knew only the first one and therefore reported "no
     * users affected" although some were assigned. Both comparisons run
     * case-insensitively — tenant names are not case-consistent in the existing data.</p>
     */
    @Transactional
    public void deleteMandat() {
        if (selectedMandat == null) {
            FacesMessages.error("Fehler", "Kein Mandat ausgewählt.");
            return;
        }

        if ("default".equalsIgnoreCase(selectedMandat)) {
            FacesMessages.error("Fehler", "Das Default-Mandat kann nicht entfernt werden.");
            return;
        }

        int zugeordnet = zugeordneteBenutzer(selectedMandat);
        if (zugeordnet > 0) {
            FacesMessages.error("Fehler", "Mandat kann nicht entfernt werden: " + zugeordnet + " Benutzer sind ihm noch "
                                    + "zugeordnet (Heimat-Mandant oder Zusatz-Mandant).");
            return;
        }

        String entfernt = selectedMandat;
        try {
            entferneMenuekonfiguration(entfernt);
        } catch (Exception e) {
            log.error("Menuekonfiguration des Mandats '{}' konnte nicht entfernt werden", entfernt, e);
            FacesMessages.error("Fehler", "Fehler beim Entfernen: " + e.getMessage());
            return;
        }

        mandate.remove(entfernt);
        selectedMandat = mandate.isEmpty() ? null : mandate.get(0);

        FacesMessages.info("Entfernt", "Mandat '" + entfernt + "' aus der Verwaltung entfernt: Menükonfiguration gelöscht, "
                                + "Mandat aus der Auswahlliste genommen. Fachdaten der Anwendung bleiben "
                                + "bestehen und müssen separat bereinigt werden.");

        log.info("Mandat '{}' aus der Verwaltung entfernt (Menuekonfiguration geloescht, Fachdaten unberuehrt)",
                entfernt);
    }

    /**
     * How many users are assigned to the tenant — in <b>both</b> forms and
     * case-insensitively.
     *
     * @param mandat the tenant to check
     * @return number of affected users (home tenant plus additional tenant, without duplicates)
     */
    int zugeordneteBenutzer(String mandat) {
        Set<String> betroffen = new HashSet<>();
        for (MyUserEntity user : users) {
            if (user != null && mandat.equalsIgnoreCase(user.getMandat())) {
                betroffen.add(kennung(user.getUsername(), user));
            }
        }
        try {
            for (UserMandate zuordnung : userMandateRepository.findByMandatIgnoreCase(mandat)) {
                if (zuordnung != null) {
                    betroffen.add(kennung(zuordnung.getUsername(), zuordnung));
                }
            }
        } catch (Exception e) {
            log.error("Zusatz-Mandate zu '{}' nicht ermittelbar — Loeschung wird vorsichtshalber blockiert",
                    mandat, e);
            return Integer.MAX_VALUE;
        }
        return betroffen.size();
    }

    /**
     * The counting key of an affected record: the lower-cased user name, so that the same
     * user counts only once across both forms. Without a name the record stays counted
     * individually — it must not disappear merely because it is incomplete.
     *
     * @param username user name, may be empty
     * @param datensatz the record, for the substitute key
     * @return counting key
     */
    private static String kennung(String username, Object datensatz) {
        if (username == null || username.isBlank()) {
            return "?" + System.identityHashCode(datensatz);
        }
        return username.toLowerCase();
    }

    /** Deletes the menu configuration of the tenant, if there is one. */
    private void entferneMenuekonfiguration(String mandat) {
        mandateMenuConfigRepository.findByMandateNameIgnoreCase(mandat)
                .ifPresent(mandateMenuConfigRepository::delete);
    }

    /**
     * Returns a sorted copy of all tenants.
     * The copy makes sure that JSF/PrimeFaces notices the changes in SelectItems.
     */
    public List<String> getAllMandate() {
        List<String> sortedMandate = new ArrayList<>(mandate);
        sortedMandate.sort(String::compareTo);
        return sortedMandate;
    }
}

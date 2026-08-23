/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.model.UserMandate;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.persistence.UserMandateRepository;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
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

    /** Die zweite Auspraegung der Mandantenzugehoerigkeit: zugeordnete Zusatz-Mandate. */
    @Autowired
    private transient UserMandateRepository userMandateRepository;

    private List<String> mandate = new ArrayList<>();
    private List<MyUserEntity> users = new ArrayList<>();
    private List<MyUserEntity> filteredUsers; // Für die Tabellen-Filter-Funktion
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
        // Behalte manuell erstellte Mandate
        List<String> existingMandate = new ArrayList<>(mandate);

        mandate.clear();
        Set<String> allMandate = plaintextSecurity.getAllMandate();
        mandate.addAll(allMandate);

        // Füge manuell erstellte Mandate hinzu, die noch nicht in der DB sind
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
        FacesContext context = FacesContext.getCurrentInstance();

        if (newMandatName == null || newMandatName.trim().isEmpty()) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Mandatname darf nicht leer sein."));
            return;
        }

        String mandatKey = newMandatName.trim().toLowerCase();

        if (mandate.contains(mandatKey)) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Mandat existiert bereits."));
            return;
        }

        try {
            // Prüfe ob Mandat bereits in der Datenbank existiert
            if (mandateMenuConfigRepository.existsByMandateName(mandatKey)) {
                context.addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Mandat existiert bereits in der Datenbank."));
                return;
            }

            // Erstelle MandateMenuConfig Entity und speichere in DB
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName(mandatKey);
            mandateMenuConfigRepository.save(config);
            log.info("Saved new mandat '{}' to database", mandatKey);

            newMandatName = "";

            // Lade Mandate neu, um sicherzustellen, dass alle Quellen berücksichtigt werden
            loadMandate();

            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Mandat '" + mandatKey + "' erstellt."));

            log.debug("Created new mandat: {}", mandatKey);

        } catch (Exception e) {
            log.error("Error creating mandat", e);
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                            "Fehler beim Erstellen des Mandats: " + e.getMessage()));
        }
    }

    public void saveUserMandat(MyUserEntity user) {
        try {
            userRepository.save(user);

            // Wenn der aktuell angemeldete Benutzer sein eigenes Mandat ändert, aktualisiere die Sitzung
            if (plaintextSecurity.getId().equals(user.getId())) {
                // Neu laden des aktuellen Benutzers
                loadMandate();
            }

            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO, "Gespeichert",
                            "Mandat für Benutzer " + user.getUsername() + " aktualisiert."));

            log.debug("Updated mandat for user {} to {}", user.getUsername(), user.getMandat());
        } catch (Exception e) {
            log.error("Error saving user mandat", e);
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                            "Fehler beim Speichern: " + e.getMessage()));
        }
    }

    /**
     * Entfernt einen Mandanten aus der <b>Verwaltung des Frameworks</b> — er verschwindet aus der
     * Auswahlliste, weil seine Menuekonfiguration ({@code mandate_menu_config}) geloescht wird.
     * Fachdaten der Anwendung bleiben unangetastet.
     *
     * <p><b>Warum nicht mehr.</b> Vorher entfernte diese Methode den Mandanten nur aus einer
     * {@code ArrayList} in der Session, ohne einen einzigen Repository-Aufruf, und meldete trotzdem
     * „Mandat geloescht" — nach dem naechsten {@link #reload()} war er wieder da. Eine echte
     * Voll-Loeschung kann root hier aber gar nicht leisten: die Fachdaten eines Mandanten liegen in
     * den Anwendungen (in plaintext-app etwa ueber acht Tabellen verteilt), und das Framework kennt
     * diese Tabellen nicht. Statt eine Voll-Loeschung vorzutaeuschen, tut die Aktion jetzt genau
     * das, was root besitzt — und sagt es auch.</p>
     *
     * <p><b>Mandantenzugehoerigkeit gibt es in zwei Auspraegungen</b>, und beide werden geprueft:
     * der Heimat-Mandant in der Rollen-Property {@code PROPERTY_MANDAT_<NAME>}
     * ({@code MyUserEntity.getMandat()}) und die Zusatz-Mandate in der Tabelle
     * {@code user_mandate}. Die frühere Pruefung kannte nur die erste und meldete deshalb „keine
     * Benutzer betroffen", obwohl welche zugeordnet waren. Beide Vergleiche laufen
     * case-insensitiv — Mandantennamen sind im Bestand nicht case-konsistent.</p>
     */
    @Transactional
    public void deleteMandat() {
        FacesContext context = FacesContext.getCurrentInstance();

        if (selectedMandat == null) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Kein Mandat ausgewählt."));
            return;
        }

        if ("default".equalsIgnoreCase(selectedMandat)) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                            "Das Default-Mandat kann nicht entfernt werden."));
            return;
        }

        int zugeordnet = zugeordneteBenutzer(selectedMandat);
        if (zugeordnet > 0) {
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                            "Mandat kann nicht entfernt werden: " + zugeordnet + " Benutzer sind ihm noch "
                                    + "zugeordnet (Heimat-Mandant oder Zusatz-Mandant)."));
            return;
        }

        String entfernt = selectedMandat;
        try {
            entferneMenuekonfiguration(entfernt);
        } catch (Exception e) {
            log.error("Menuekonfiguration des Mandats '{}' konnte nicht entfernt werden", entfernt, e);
            context.addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, "Fehler",
                            "Fehler beim Entfernen: " + e.getMessage()));
            return;
        }

        mandate.remove(entfernt);
        selectedMandat = mandate.isEmpty() ? null : mandate.get(0);

        context.addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Entfernt",
                        "Mandat '" + entfernt + "' aus der Verwaltung entfernt: Menükonfiguration gelöscht, "
                                + "Mandat aus der Auswahlliste genommen. Fachdaten der Anwendung bleiben "
                                + "bestehen und müssen separat bereinigt werden."));

        log.info("Mandat '{}' aus der Verwaltung entfernt (Menuekonfiguration geloescht, Fachdaten unberuehrt)",
                entfernt);
    }

    /**
     * Wie viele Benutzer dem Mandanten zugeordnet sind — in <b>beiden</b> Auspraegungen und
     * case-insensitiv.
     *
     * @param mandat der zu pruefende Mandant
     * @return Anzahl betroffener Benutzer (Heimat-Mandant plus Zusatz-Mandat, ohne Dubletten)
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
     * Der Zaehlschluessel eines Betroffenen: der kleingeschriebene Benutzername, damit derselbe
     * Benutzer in beiden Auspraegungen nur einmal zaehlt. Ohne Namen bleibt der Datensatz einzeln
     * gezaehlt — er darf nicht verschwinden, nur weil er unvollstaendig ist.
     *
     * @param username Benutzername, darf leer sein
     * @param datensatz der Datensatz, fuer den Ersatzschluessel
     * @return Zaehlschluessel
     */
    private static String kennung(String username, Object datensatz) {
        if (username == null || username.isBlank()) {
            return "?" + System.identityHashCode(datensatz);
        }
        return username.toLowerCase();
    }

    /** Loescht die Menuekonfiguration des Mandanten, falls vorhanden. */
    private void entferneMenuekonfiguration(String mandat) {
        mandateMenuConfigRepository.findByMandateNameIgnoreCase(mandat)
                .ifPresent(mandateMenuConfigRepository::delete);
    }

    /**
     * Gibt eine sortierte Kopie aller Mandate zurück.
     * Die Kopie stellt sicher, dass JSF/PrimeFaces die Änderungen in SelectItems erkennt.
     */
    public List<String> getAllMandate() {
        List<String> sortedMandate = new ArrayList<>(mandate);
        sortedMandate.sort(String::compareTo);
        return sortedMandate;
    }
}

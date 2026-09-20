/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.settings.SettingsKeys;
import ch.plaintext.settings.entity.Setting;
import ch.plaintext.settings.service.SettingsServiceImpl;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
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
public class SettingsBackingBean implements Serializable {

    // Feldinjektion, nicht Konstruktorinjektion (Karte 1269). Diese Bohne ist session-scoped und
    // serialisierbar: bei einer Deserialisierung laeuft KEIN Konstruktor. Als `final` gesetzte
    // Dienste blieben danach dauerhaft null, und final liesse sich auch nachtraeglich nicht mehr
    // setzen — aus einer NotSerializableException wuerde eine dauerhafte NullPointerException
    // (Karten 915/1246). Ueber @Autowired fuellt der Kontext die Felder nach dem Aufwachen wieder.
    // Das ist die Hausregel; java:S6813 gilt hier bewusst nicht.
    @Autowired
    private transient SettingsServiceImpl service;
    @Autowired
    private transient PlaintextSecurity security;

    private List<Setting> settings;
    private Setting selected;
    private String searchFilter;
    private boolean root;

    /**
     * preRenderView listener (session-scoped instead of @ViewScoped): sets the role, locks out non-ROOT
     * (redirect) and loads the data FRESH on every page call (GET). The isPostback guard prevents
     * the reload on every Ajax postback. Replaces the former @PostConstruct init() + checkAccess().
     */
    public void onLoad() {
        root = security.ifGranted("ROLE_ROOT");
        if (!root) {
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
                // Karte 1063: die globalen Eintraege gehoeren mit in die Liste. Sie wirken fuer
                // diesen Mandanten (der Lesepfad faellt auf sie zurueck) — eine Einstellung, die
                // wirkt, aber nicht auffindbar ist, waere schlimmer als gar keine.
                settings = new ArrayList<>(service.getAllSettings(security.getMandat()));
                if (!SettingsKeys.MANDAT_GLOBAL.equals(security.getMandat())) {
                    settings.addAll(service.getAllSettings(SettingsKeys.MANDAT_GLOBAL));
                }
            } else {
                settings = new ArrayList<>();
            }
        } catch (Exception e) {
            log.error("Error loading settings", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Daten konnten nicht geladen werden");
        }
    }

    public void select() {
        // Selected in UI
    }

    public void clearSelection() {
        selected = null;
    }

    public void newSetting() {
        selected = new Setting();
        selected.setKey("");
        selected.setMandat(security.getMandat());
        selected.setValue("");
        selected.setValueType("STRING");
        selected.setDescription("");
    }

    // Helper methods for type-specific values
    public Boolean getBooleanValue() {
        if (selected == null || selected.getValue() == null) {
            return false;
        }
        return Boolean.parseBoolean(selected.getValue());
    }

    public void setBooleanValue(Boolean value) {
        if (selected != null) {
            selected.setValue(value != null ? value.toString() : "false");
        }
    }

    public java.time.LocalDateTime getDateValue() {
        if (selected == null || selected.getValue() == null || selected.getValue().trim().isEmpty()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(selected.getValue(), java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("Cannot parse date value: {}", selected.getValue());
            return null;
        }
    }

    public void setDateValue(java.time.LocalDateTime value) {
        if (selected != null) {
            selected.setValue(value != null ? value.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "");
        }
    }

    public void save() {
        if (selected == null) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Einstellung ausgewählt");
            return;
        }

        if (selected.getKey() == null || selected.getKey().trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Schlüssel ist erforderlich");
            return;
        }

        if (selected.getMandat() == null || selected.getMandat().trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Mandat ist erforderlich");
            return;
        }

        try {
            service.setSetting(selected.getKey(), selected.getMandat(), selected.getValue(),
                             selected.getValueType(), selected.getDescription());
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Einstellung gespeichert");
            loadData();
        } catch (Exception e) {
            log.error("Error saving setting", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Speichern fehlgeschlagen");
        }
    }

    public void delete() {
        if (selected == null) {
            addMessage(FacesMessage.SEVERITY_WARN, "Warnung", "Keine Einstellung ausgewählt");
            return;
        }

        try {
            service.deleteSetting(selected.getKey(), selected.getMandat());
            addMessage(FacesMessage.SEVERITY_INFO, "Erfolg", "Einstellung gelöscht");
            selected = null;
            loadData();
        } catch (Exception e) {
            log.error("Error deleting setting", e);
            addMessage(FacesMessage.SEVERITY_ERROR, "Fehler", "Löschen fehlgeschlagen");
        }
    }

    public List<Setting> getFilteredSettings() {
        if (searchFilter == null || searchFilter.trim().isEmpty()) {
            return settings;
        }
        String filter = searchFilter.toLowerCase();
        return settings.stream()
                .filter(s -> s.getKey().toLowerCase().contains(filter) ||
                           (s.getValue() != null && s.getValue().toLowerCase().contains(filter)) ||
                           (s.getDescription() != null && s.getDescription().toLowerCase().contains(filter)))
                .toList();
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesMessages.meldung(severity, summary, detail);
    }

    /**
     * Die waehlbaren Mandanten — plus {@link SettingsKeys#MANDAT_GLOBAL} (Karte 1063, Auftrag
     * Daniel 05.09.2026: „gleich wie bei Cron, welcher fuer alle mandate gelten kann").
     *
     * <p>Genau so macht es {@code CronController.createCronsMap()}: die Mandantenliste, ergaenzt um
     * den reservierten Wert. Er steht bewusst <b>vorn</b> — wer eine Einstellung fuer alle anlegen
     * will, sucht sie nicht am Ende einer Mandantenliste.
     */
    public List<String> getAllMandate() {
        List<String> mandate = new ArrayList<>();
        mandate.add(SettingsKeys.MANDAT_GLOBAL);
        security.getAllMandate().stream()
                .filter(m -> !SettingsKeys.MANDAT_GLOBAL.equals(m))
                .sorted()
                .forEach(mandate::add);
        return mandate;
    }

    /**
     * Ob diese Zeile ein globaler Eintrag ist — fuer die Kennzeichnung in der Liste.
     *
     * @param setting die Zeile
     * @return {@code true} bei einem Eintrag, der fuer alle Mandanten gilt
     */
    public boolean istGlobal(Setting setting) {
        return setting != null && SettingsKeys.MANDAT_GLOBAL.equals(setting.getMandat());
    }

    public List<String> getValueTypes() {
        return List.of("STRING", "INTEGER", "BOOLEAN", "DATE", "LIST");
    }
}

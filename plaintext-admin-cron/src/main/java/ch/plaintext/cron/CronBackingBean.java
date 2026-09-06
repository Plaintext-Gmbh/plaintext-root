/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.utils.TimeUtil;
import it.sauronsoftware.cron4j.Predictor;
import it.sauronsoftware.cron4j.SchedulingPattern;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@Named("cronBackingBean")
@Scope("session")
public class CronBackingBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @Autowired
    private CronController cronController;

    @Autowired
    private PlaintextSecurity plaintextSecurity;

    @Getter
    private List<CronConfigEntity> crons = new ArrayList<>();

    @Getter
    @Setter
    private String crone;

    private CronConfigEntity selected;

    @PostConstruct
    public void init() {
        refreshCrons();
    }

    public void refreshCrons() {
        String mandat = plaintextSecurity.getMandat();
        crons.clear();

        if(plaintextSecurity.ifGranted("root")){
            // Root sees ALL cron entries from all tenants
            for (List<CronConfigEntity> mandatList : cronController.getCronsMap().values()) {
                if (mandatList != null) {
                    crons.addAll(mandatList);
                }
            }
        } else {
            // Non-root users only see their own tenant entries
            List<CronConfigEntity> list = cronController.getCronsMap().get(mandat);
            if (list != null) {
                crons.addAll(list);
            }
        }
    }

    public void select(String name, String mandat) {
        selected = findCron(name, mandat);
        if (selected != null) {
            crone = selected.getCronExpression();
            if (crone != null && !crone.isEmpty()) {
                validate();
            }
        }
    }

    public void validate() {
        FacesContext fc = FacesContext.getCurrentInstance();

        if (crone == null || crone.isEmpty()) {
            FacesMessages.error("Wert ist nicht gesetzt", "");
            return;
        }

        if (SchedulingPattern.validate(crone)) {
            Predictor pr = new Predictor(crone);
            FacesMessages.info("Naechste Ausfuehrungen:", "");
            for (int i = 0; i < 5; i++) {
                FacesMessages.info(TimeUtil.getDateTimeAsStringWochentag(pr.nextMatchingDate()), "");
            }
        } else {
            FacesMessages.error("Wert ist nicht gueltig", "");
        }
    }

    public void save() {
        if (selected == null) {
            FacesMessages.warn("Kein Cron selektiert", "");
            return;
        }

        selected.setCronExpression(crone);
        cronController.save(selected);

        // Reschedule the cron if it's enabled
        if (selected.isEnabled()) {
            cronController.unschedule(selected);
            cronController.schedule(selected);
        }

        FacesMessages.info("Cron wurde gespeichert und neu geplant", "");

        refreshCrons();
    }

    public void toggleEnabled(String name, String mandat) {
        CronConfigEntity cfg = findCron(name, mandat);
        if (cfg != null) {
            cfg.setEnabled(!cfg.isEnabled());
            cfg = cronController.save(cfg);
            if(cfg.isEnabled()){
                cronController.schedule(cfg);
            } else {
                cronController.unschedule(cfg);
            }
            refreshCrons();
        }
    }

    public void toggleStartup(String name, String mandat) {
        CronConfigEntity cfg = findCron(name, mandat);
        if (cfg != null) {
            cfg.setStartup(!cfg.isStartup());
            cronController.save(cfg);
            refreshCrons();
        }
    }

    public void trigger(String name, String mandat) {
        CronConfigEntity cfg = findCron(name, mandat);
        if (cfg != null) {
            cronController.trigger(cfg.getCronName(), cfg.getMandat());
            FacesMessages.info("Cron Job wird ausgeführt", "Der Cron Job '" + cfg.getDisplayName() + "' wurde manuell gestartet");
            refreshCrons();
        } else {
            FacesMessages.warn("Cron Job nicht gefunden", "Der Cron Job '" + name + "' (Mandat: " + mandat + ") konnte nicht gefunden werden");
        }
    }

    public boolean isEnabled(String name, String mandat) {
        CronConfigEntity cfg = findCron(name, mandat);
        return cfg != null && cfg.isEnabled();
    }

    public boolean isStartup(String name, String mandat) {
        CronConfigEntity cfg = findCron(name, mandat);
        return cfg != null && cfg.isStartup();
    }

    /**
     * Find cron by name and mandant
     */
    private CronConfigEntity findCron(String name, String mandat) {
        for (CronConfigEntity c : crons) {
            if (c != null && name.equals(c.getCronName()) && mandat.equals(c.getMandat())) {
                return c;
            }
        }
        return null;
    }

    /**
     * Find cron by name only (for backward compatibility with isEnabled/isStartup)
     * Prefers current user's mandant when multiple entries exist
     */
    private CronConfigEntity findCron(String name) {
        // When multiple entries exist with same cronName but different mandant,
        // we need to prioritize the current user's mandant
        String currentMandat = plaintextSecurity.getMandat();

        // First try: find exact match with current mandant
        for (CronConfigEntity c : crons) {
            if (c != null && name.equals(c.getCronName()) && currentMandat.equals(c.getMandat())) {
                return c;
            }
        }

        // Fallback: return first match (for backward compatibility)
        for (CronConfigEntity c : crons) {
            if (c != null && name.equals(c.getCronName())) {
                return c;
            }
        }

        return null;
    }

    public java.util.Date getNextRun(CronConfigEntity entity) {
        if (entity == null || entity.getCronExpression() == null || entity.getCronExpression().isEmpty()) {
            return null;
        }
        try {
            Predictor pr = new Predictor(entity.getCronExpression());
            return pr.nextMatchingDate();
        } catch (Exception e) {
            log.error("Error calculating next run for: " + entity.getCronName(), e);
            return null;
        }
    }

    public String getWann(CronConfigEntity entity) {
        if (entity == null || entity.getCronExpression() == null || entity.getCronExpression().isEmpty()) {
            return "-";
        }
        try {
            org.ocpsoft.prettytime.PrettyTime t = new org.ocpsoft.prettytime.PrettyTime(new java.util.Date());
            t.setLocale(java.util.Locale.GERMAN);
            Predictor pr = new Predictor(entity.getCronExpression());
            java.util.Date d = pr.nextMatchingDate();
            return t.format(d);
        } catch (Exception e) {
            log.error("Error calculating next run description for: " + entity.getCronName(), e);
            return "-";
        }
    }

    /**
     * Check if the current user has root role
     */
    public boolean isRoot() {
        return plaintextSecurity.ifGranted("root");
    }

    /** German display label for {@link CronConfigEntity#getScope()} (Task 005). */
    public String getScopeLabel(CronConfigEntity entity) {
        if (entity == null || entity.getScope() == null) {
            return "-";
        }
        return switch (entity.getScope()) {
            case APPLICATION -> "Application";
            case MANDAT -> "Mandat";
            case PERSOENLICH -> "Persönlich";
        };
    }
}

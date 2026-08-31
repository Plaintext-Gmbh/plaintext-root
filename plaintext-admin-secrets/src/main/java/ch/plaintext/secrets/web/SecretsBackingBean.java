/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets.web;

import ch.plaintext.secrets.SecretBackendType;
import ch.plaintext.secrets.SecretEntry;
import ch.plaintext.secrets.SecretHealth;
import ch.plaintext.secrets.SecretService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;

/**
 * Backing Bean for {@code secrets.xhtml}: list of the managed secrets (without values — one-way),
 * setting/creating, backend settings and password generator on a single page.
 */
@Component("secretsBean")
@Scope("session")
@Data
@Slf4j
public class SecretsBackingBean implements Serializable {

    @Autowired
    private transient SecretService secretService;

    private List<SecretEntry> secrets;
    private boolean devFallbackKey;

    // Form: set/create a secret
    private String name;
    private SecretBackendType backendType = SecretBackendType.VAULTWARDEN;
    private String value;
    private String note;

    // Settings: active backend
    private SecretBackendType activeBackend;
    private String backendConfig;
    private boolean configured;        // is a backend already firmly configured? (then locked)
    private transient SecretHealth health;   // live test of the active backend

    // Migration (switch backend + move all secrets)
    private boolean migrateMode;
    private SecretBackendType migrateTarget;
    private String migrateConfig;

    // Password generator
    private int genLength = 24;
    private boolean genLower = true;
    private boolean genUpper = true;
    private boolean genDigits = true;
    private boolean genSymbols = true;
    private String generated;

    @PostConstruct
    public void onLoad() {
        refresh();
    }

    public void refresh() {
        secrets = secretService.list();
        activeBackend = secretService.activeBackend();
        configured = secretService.isConfigured();
        health = secretService.health();
        devFallbackKey = secretService.isDevFallbackKey();
    }

    public SecretBackendType[] getBackendTypes() {
        return SecretBackendType.values();
    }

    // EL-compatible getters for the record SecretHealth (records have no isXxx/getXxx accessors).
    public boolean isHealthOk() {
        return health != null && health.ok();
    }

    public String getHealthDetail() {
        return health == null ? null : health.detail();
    }

    /** Set/create a secret (one-way). The value is discarded immediately after saving. */
    public void save() {
        try {
            if (name == null || name.isBlank()) {
                warn("Name erforderlich.");
                return;
            }
            secretService.set(name.trim(), backendType, value, note);
            info("Secret '" + name.trim() + "' gesetzt (" + backendType + ").");
            name = null;
            value = null;
            note = null;
            generated = null;
            refresh();
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    public void delete(SecretEntry entry) {
        try {
            secretService.delete(entry.getId());
            info("Secret '" + entry.getName() + "' entfernt.");
            refresh();
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    /** Initial configuration of the backend. Afterwards it is locked — a switch only via migration. */
    public void saveBackend() {
        try {
            if (configured) {
                warn("Backend ist bereits konfiguriert — ein Wechsel ist nur über Migrieren möglich.");
                return;
            }
            secretService.setActiveBackend(activeBackend, backendConfig);
            info("Backend konfiguriert: " + activeBackend + ".");
            backendConfig = null;
            refresh();
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    /** Opens the migration area (switch backend). Pre-filled with one other than the active one. */
    public void startMigrate() {
        migrateMode = true;
        migrateConfig = null;
        migrateTarget = null;
        for (SecretBackendType t : SecretBackendType.values()) {
            if (t != activeBackend) {
                migrateTarget = t;
                break;
            }
        }
    }

    public void cancelMigrate() {
        migrateMode = false;
        migrateTarget = null;
        migrateConfig = null;
    }

    /** Migrates all secrets to the selected target backend and switches that one active. */
    public void migrate() {
        try {
            if (migrateTarget == null) {
                warn("Ziel-Backend wählen.");
                return;
            }
            if (migrateTarget == activeBackend) {
                warn("Ziel-Backend ist bereits aktiv.");
                return;
            }
            SecretService.MigrationResult r = secretService.migrate(migrateTarget, migrateConfig);
            info("Migration " + r.from() + " → " + r.to() + ": " + r.migrated() + " Secrets gezügelt"
                    + (r.skipped() > 0 ? ", " + r.skipped() + " ohne lesbaren Wert (nur umgehängt)." : "."));
            cancelMigrate();
            refresh();
        } catch (RuntimeException e) {
            error(e.getMessage());
        }
    }

    /** Loads an existing entry into the set form in order to change its value in the backend. */
    public void edit(SecretEntry entry) {
        name = entry.getName();
        backendType = entry.getBackendType();
        note = entry.getNote();
        value = null;
        info("Eintrag '" + entry.getName() + "' geladen — neuen Wert eingeben und speichern.");
    }

    /** Generates a password and writes it into the value field (for setting it directly). */
    public void generate() {
        generated = secretService.generatePassword(genLength, genLower, genUpper, genDigits, genSymbols);
        value = generated;
    }

    private void info(String m) {
        msg(FacesMessage.SEVERITY_INFO, "Secrets", m);
    }

    private void warn(String m) {
        msg(FacesMessage.SEVERITY_WARN, "Secrets", m);
    }

    private void error(String m) {
        msg(FacesMessage.SEVERITY_ERROR, "Fehler", m);
    }

    private void msg(FacesMessage.Severity s, String t, String m) {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            ctx.addMessage(null, new FacesMessage(s, t, m));
        }
    }
}

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
 * Backing-Bean für {@code secrets.xhtml}: Liste der verwalteten Secrets (ohne Werte — one-way),
 * Setzen/Anlegen, Backend-Settings und Passwort-Generator auf einer Seite.
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

    // Formular: Secret setzen/anlegen
    private String name;
    private SecretBackendType backendType = SecretBackendType.VAULTWARDEN;
    private String value;
    private String note;

    // Settings: aktives Backend
    private SecretBackendType activeBackend;
    private String backendConfig;
    private boolean configured;        // ist bereits ein Backend fix konfiguriert? (dann gesperrt)
    private transient SecretHealth health;   // Live-Test des aktiven Backends

    // Migration (Backend wechseln + alle Secrets zügeln)
    private boolean migrateMode;
    private SecretBackendType migrateTarget;
    private String migrateConfig;

    // Passwort-Generator
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

    // EL-taugliche Getter fürs Record SecretHealth (Records haben keine isXxx/getXxx-Accessoren).
    public boolean isHealthOk() {
        return health != null && health.ok();
    }

    public String getHealthDetail() {
        return health == null ? null : health.detail();
    }

    /** Secret setzen/anlegen (one-way). Der Wert wird nach dem Speichern sofort verworfen. */
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

    /** Erst-Konfiguration des Backends. Danach ist es gesperrt — Wechsel nur noch via Migration. */
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

    /** Öffnet den Migrations-Bereich (Backend wechseln). Vorbelegt mit einem anderen als dem aktiven. */
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

    /** Migriert alle Secrets zum gewählten Ziel-Backend und schaltet dieses aktiv. */
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

    /** Lädt einen bestehenden Eintrag ins Setzen-Formular, um seinen Wert im Backend zu ändern. */
    public void edit(SecretEntry entry) {
        name = entry.getName();
        backendType = entry.getBackendType();
        note = entry.getNote();
        value = null;
        info("Eintrag '" + entry.getName() + "' geladen — neuen Wert eingeben und speichern.");
    }

    /** Generiert ein Passwort und schreibt es ins Wert-Feld (zum direkten Setzen). */
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

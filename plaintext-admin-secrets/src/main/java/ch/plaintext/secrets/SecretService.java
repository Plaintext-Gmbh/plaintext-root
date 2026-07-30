/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Kern-Service des Secrets-Moduls: einzige Stelle, die {@code secret_entry} (Metadaten/Audit) pflegt und
 * pro Backend-Typ die Wert-Operation delegiert. Werte werden NIE zurückgelesen/angezeigt (one-way):
 * LOCAL_DB verschlüsselt der Service selbst, VAULTWARDEN geht an {@link VaultwardenSecretBackend}.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SecretService {

    private final SecretEntryRepository entryRepo;
    private final SecretBackendConfigRepository configRepo;
    private final SecretCrypto crypto;
    private final VaultwardenSecretBackend vaultwarden;
    private final HashiCorpVaultBackend hashicorp;
    private final PasswordGenerator generator;

    /** Verwaltete Secrets des Mandanten (mit live-Kommentar bei Vaultwarden). Nie mit Werten. */
    public List<SecretEntry> list() {
        String mandat = PlaintextSecurityHolder.getMandat();
        List<SecretEntry> entries = entryRepo.findByMandatAndDeletedOrderByNameAsc(mandat, false);
        entries.forEach(e -> {
            if (e.getBackendType() == SecretBackendType.VAULTWARDEN && e.getComment() == null) {
                try {
                    e.setComment(vaultwarden.comment(e.getName()));
                } catch (RuntimeException ex) {
                    log.debug("Vaultwarden-Kommentar fuer {} nicht lesbar: {}", e.getName(), ex.getMessage());
                }
            }
        });
        return entries;
    }

    /**
     * Secret setzen/anlegen (one-way). LOCAL_DB → AES-GCM in {@code wert_encrypted}; VAULTWARDEN → in den
     * Tresor schreiben. Metadaten (Name, Backend, Notiz, {@code createdDate}=Neueintragung) in secret_entry.
     */
    @Transactional
    public SecretEntry set(String name, SecretBackendType backend, String value, String note) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name erforderlich");
        }
        String mandat = PlaintextSecurityHolder.getMandat();
        SecretEntry entry = entryRepo.findByMandatAndName(mandat, name).orElseGet(() -> {
            SecretEntry n = new SecretEntry();
            n.setName(name.trim());
            n.setMandat(mandat);
            n.setDeleted(false);
            return n;
        });
        entry.setBackendType(backend);
        if (note != null) {
            entry.setNote(note);
        }
        if (backend == SecretBackendType.LOCAL_DB) {
            entry.setWertEncrypted(value == null ? entry.getWertEncrypted() : crypto.encrypt(value));
        } else if (backend == SecretBackendType.VAULTWARDEN) {
            if (value != null && !value.isEmpty()) {
                vaultwarden.set(name, value, note);
            }
            entry.setWertEncrypted(null);
        } else {
            throw new UnsupportedOperationException("Backend " + backend + " noch nicht implementiert (Phase 4)");
        }
        return entryRepo.save(entry);
    }

    /** Soft-Delete eines verwalteten Secrets (Metadaten-Eintrag; externer Wert bleibt unangetastet). */
    @Transactional
    public void delete(Long id) {
        String mandat = PlaintextSecurityHolder.getMandat();
        entryRepo.findById(id)
                .filter(e -> mandat != null && mandat.equals(e.getMandat()))
                .ifPresent(e -> {
                    e.setDeleted(true);
                    entryRepo.save(e);
                });
    }

    /** Interne Wert-Auflösung für LOCAL_DB (Entschlüsselung) — NICHT fürs UI. */
    public Optional<String> resolveLocalValue(String name) {
        String mandat = PlaintextSecurityHolder.getMandat();
        return entryRepo.findByMandatAndName(mandat, name)
                .filter(e -> e.getBackendType() == SecretBackendType.LOCAL_DB && e.getWertEncrypted() != null)
                .map(e -> crypto.decrypt(e.getWertEncrypted()));
    }

    // ── Backend-Settings ─────────────────────────────────────

    public SecretBackendType activeBackend() {
        String mandat = PlaintextSecurityHolder.getMandat();
        return configRepo.findFirstByMandatAndAktivAndDeleted(mandat, true, false)
                .map(SecretBackendConfig::getBackendType)
                .orElse(SecretBackendType.VAULTWARDEN);
    }

    @Transactional
    public void setActiveBackend(SecretBackendType type, String configJson) {
        String mandat = PlaintextSecurityHolder.getMandat();
        configRepo.findByMandatAndDeleted(mandat, false).forEach(c -> {
            c.setAktiv(false);
            configRepo.save(c);
        });
        SecretBackendConfig cfg = configRepo.findFirstByMandatAndAktivAndDeleted(mandat, false, false)
                .orElseGet(SecretBackendConfig::new);
        cfg.setBackendType(type);
        cfg.setMandat(mandat);
        cfg.setDeleted(false);
        cfg.setAktiv(true);
        if (configJson != null && !configJson.isBlank()) {
            cfg.setConfigEncrypted(crypto.encrypt(configJson));   // Zugriffstoken/JSON verschlüsselt ablegen
        }
        configRepo.save(cfg);
    }

    /** Ist bereits ein aktives Backend konfiguriert? (Erst-Konfiguration vs. gesperrt/Migration.) */
    public boolean isConfigured() {
        String mandat = PlaintextSecurityHolder.getMandat();
        return configRepo.findFirstByMandatAndAktivAndDeleted(mandat, true, false).isPresent();
    }

    /** Live-Test des AKTIVEN Backends fürs UI: greift es, und falls nicht — was fehlt? */
    public SecretHealth health() {
        return switch (activeBackend()) {
            case LOCAL_DB -> crypto.isDevFallback()
                    ? SecretHealth.down("PLAINTEXT_SECRET_KEY nicht gesetzt — Dev-Fallback aktiv. "
                            + "Für PROD einen random base64-32-Byte-Key per Docker-Env setzen.")
                    : SecretHealth.up("Lokale DB-Verschlüsselung aktiv (Schlüssel gesetzt).");
            case VAULTWARDEN -> vaultwarden.health();
            case HASHICORP -> hashicorp.health();
        };
    }

    private SecretBackend backendFor(SecretBackendType t) {
        return switch (t) {
            case VAULTWARDEN -> vaultwarden;
            case HASHICORP -> hashicorp;
            case LOCAL_DB -> null;
        };
    }

    /**
     * Zügelt ALLE verwalteten Secrets vom aktiven Backend zum neuen und schaltet dieses aktiv. Werte
     * werden serverseitig gelesen/geschrieben (nie im UI). Reihenfolge: erst ALLE Werte vom alten (noch
     * aktiven) Backend lesen, dann umschalten, dann ins neue schreiben — sonst kollidieren read-old und
     * write-new über die eine aktive Config.
     */
    @Transactional
    public MigrationResult migrate(SecretBackendType newType, String newConfigJson) {
        if (newType == null) {
            throw new IllegalArgumentException("Ziel-Backend erforderlich");
        }
        String mandat = PlaintextSecurityHolder.getMandat();
        SecretBackendType oldType = activeBackend();
        List<SecretEntry> entries = entryRepo.findByMandatAndDeletedOrderByNameAsc(mandat, false);

        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();   // Phase 1: alt lesen
        for (SecretEntry e : entries) {
            String v = readValueInternal(oldType, e);
            if (v != null) {
                values.put(e.getName(), v);
            }
        }

        setActiveBackend(newType, newConfigJson);                                  // Phase 2: umschalten
        SecretHealth h = health();
        if (!h.ok() && newType != SecretBackendType.LOCAL_DB) {
            throw new IllegalStateException("Ziel-Backend greift nicht: " + h.detail());
        }

        int migrated = 0;                                                          // Phase 3: neu schreiben
        int skipped = 0;
        for (SecretEntry e : entries) {
            String v = values.get(e.getName());
            e.setBackendType(newType);
            if (v == null) {
                skipped++;
            } else if (newType == SecretBackendType.LOCAL_DB) {
                e.setWertEncrypted(crypto.encrypt(v));
                migrated++;
            } else {
                backendFor(newType).set(e.getName(), v, e.getNote());
                e.setWertEncrypted(null);
                migrated++;
            }
            entryRepo.save(e);
        }
        log.info("Secret-Migration {} -> {}: {} gezügelt, {} ohne lesbaren Wert", oldType, newType, migrated, skipped);
        return new MigrationResult(oldType, newType, migrated, skipped);
    }

    private String readValueInternal(SecretBackendType type, SecretEntry e) {
        return switch (type) {
            case LOCAL_DB -> e.getWertEncrypted() == null ? null : crypto.decrypt(e.getWertEncrypted());
            case VAULTWARDEN -> vaultwarden.readValue(e.getName());
            case HASHICORP -> hashicorp.readValue(e.getName());
        };
    }

    /** Ergebnis einer Backend-Migration (fürs UI-Feedback). */
    public record MigrationResult(SecretBackendType from, SecretBackendType to, int migrated, int skipped) { }

    // ── Passwort-Generator ───────────────────────────────────

    public String generatePassword(int length, boolean lower, boolean upper, boolean digits, boolean symbols) {
        return generator.generate(length, lower, upper, digits, symbols);
    }

    public boolean isDevFallbackKey() {
        return crypto.isDevFallback();
    }
}

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
 * Core service of the secrets module: the single place that maintains {@code secret_entry}
 * (metadata/audit) and delegates the value operation per backend type. Values are NEVER read back or
 * displayed (one-way): for LOCAL_DB the service encrypts them itself, VAULTWARDEN goes to
 * {@link VaultwardenSecretBackend}.
 *
 * <p>The only exception to the one-way principle is {@link #resolve(String)} from
 * {@link SecretResolver} — plaintext for technical consumers, explicitly not for the UI. See there.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SecretService implements SecretResolver {

    private final SecretEntryRepository entryRepo;
    private final SecretBackendConfigRepository configRepo;
    private final SecretCrypto crypto;
    private final VaultwardenSecretBackend vaultwarden;
    private final HashiCorpVaultBackend hashicorp;
    private final PasswordGenerator generator;

    /** Managed secrets of the tenant (with live comment for Vaultwarden). Never with values. */
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
     * Set/create a secret (one-way). LOCAL_DB → AES-GCM in {@code wert_encrypted}; VAULTWARDEN and
     * HASHICORP → written to the respective vault. Metadata (name, backend, note,
     * {@code createdDate}=first entry) in secret_entry.
     *
     * <p><b>The backend is attached to the entry, not to the instance</b> (see
     * {@link #resolve(String)}): a single entry can be set to HASHICORP while all others stay where
     * they are. That is precisely what makes moving a secret individual and reversible — the way back
     * is another {@code set} with the old backend, without running
     * {@link #migrate(SecretBackendType, String)} over the whole inventory.</p>
     *
     * <p>Card 855: until 2026-08-30 HASHICORP threw {@code UnsupportedOperationException} here
     * ("Phase 4"), even though {@link HashiCorpVaultBackend#set(String, String, String)} existed and
     * {@link #readValue(SecretEntry)} had long been reading from the backend. Reading worked, writing
     * did not — an entry could therefore only be switched to HASHICORP by hand in the database.</p>
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
        // switch over the enum instead of if/else: the compiler enforces that a future backend is
        // handled here instead of silently falling into an else branch.
        switch (backend) {
            case LOCAL_DB -> entry.setWertEncrypted(
                    value == null ? entry.getWertEncrypted() : crypto.encrypt(value));
            case VAULTWARDEN, HASHICORP -> {
                // Empty value = only change metadata (note, backend switch of an already stored
                // value). A write call with an empty value would overwrite the vault entry.
                if (value != null && !value.isEmpty()) {
                    backendFor(backend).set(name, value, note);
                }
                entry.setWertEncrypted(null);
            }
        }
        return entryRepo.save(entry);
    }

    /** Soft delete of a managed secret (metadata entry; the external value stays untouched). */
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

    /**
     * {@inheritDoc}
     *
     * <p>Decisive is the backend <b>of the entry</b>, not {@link #activeBackend()}: the latter is only
     * the default for newly created secrets. After a backend switch without
     * {@link #migrate(SecretBackendType, String)} the values still reside where they were created —
     * if the active backend were asked here, the resolution would silently return nothing.
     *
     * <p>Errors of a backend are damped down to {@link Optional#empty()} and logged. An unreachable
     * vault should lead the caller to its fallback, not abort the calling function.
     */
    @Override
    public Optional<String> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String mandat = PlaintextSecurityHolder.getMandat();
        return entryRepo.findByMandatAndName(mandat, name.trim())
                .filter(e -> !Boolean.TRUE.equals(e.getDeleted()))
                .flatMap(this::readValue)
                .filter(v -> !v.isEmpty());
    }

    private Optional<String> readValue(SecretEntry entry) {
        try {
            return Optional.ofNullable(switch (entry.getBackendType()) {
                case LOCAL_DB -> entry.getWertEncrypted() == null ? null : crypto.decrypt(entry.getWertEncrypted());
                case VAULTWARDEN -> vaultwarden.readValue(entry.getName());
                case HASHICORP -> hashicorp.readValue(entry.getName());
            });
        } catch (RuntimeException ex) {
            log.warn("Secret '{}' ({}) nicht lesbar: {}", entry.getName(), entry.getBackendType(), ex.getMessage());
            return Optional.empty();
        }
    }

    /** Internal value resolution for LOCAL_DB (decryption) — NOT for the UI. */
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
            cfg.setConfigEncrypted(crypto.encrypt(configJson));   // store access token/JSON encrypted
        }
        configRepo.save(cfg);
    }

    /** Is an active backend already configured? (Initial configuration vs. locked/migration.) */
    public boolean isConfigured() {
        String mandat = PlaintextSecurityHolder.getMandat();
        return configRepo.findFirstByMandatAndAktivAndDeleted(mandat, true, false).isPresent();
    }

    /** Live test of the ACTIVE backend for the UI: does it work, and if not — what is missing? */
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
     * Moves ALL managed secrets from the active backend to the new one and switches that one active.
     * Values are read/written server-side (never in the UI). Order: first read ALL values from the old
     * (still active) backend, then switch over, then write into the new one — otherwise read-old and
     * write-new collide over the single active config.
     */
    @Transactional
    public MigrationResult migrate(SecretBackendType newType, String newConfigJson) {
        if (newType == null) {
            throw new IllegalArgumentException("Ziel-Backend erforderlich");
        }
        String mandat = PlaintextSecurityHolder.getMandat();
        SecretBackendType oldType = activeBackend();
        List<SecretEntry> entries = entryRepo.findByMandatAndDeletedOrderByNameAsc(mandat, false);

        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();   // phase 1: read old
        for (SecretEntry e : entries) {
            String v = readValueInternal(oldType, e);
            if (v != null) {
                values.put(e.getName(), v);
            }
        }

        setActiveBackend(newType, newConfigJson);                                  // phase 2: switch over
        SecretHealth h = health();
        if (!h.ok() && newType != SecretBackendType.LOCAL_DB) {
            throw new IllegalStateException("Ziel-Backend greift nicht: " + h.detail());
        }

        int migrated = 0;                                                          // phase 3: write new
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

    /** Result of a backend migration (for UI feedback). */
    public record MigrationResult(SecretBackendType from, SecretBackendType to, int migrated, int skipped) { }

    // ── Password generator ───────────────────────────────────

    public String generatePassword(int length, boolean lower, boolean upper, boolean digits, boolean symbols) {
        return generator.generate(length, lower, upper, digits, symbols);
    }

    public boolean isDevFallbackKey() {
        return crypto.isDevFallback();
    }
}

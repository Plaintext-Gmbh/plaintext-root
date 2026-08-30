/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * Public, fail-safe API for consumer apps to obtain secrets from
 * their own Vaultwarden instance.
 *
 * <p><b>Item matching:</b> first an exact (case-insensitive) name match;
 * if there is none, the first item whose name <i>contains</i> the searched
 * string (case-insensitive) is used. That allows short search terms such as
 * {@code "Paperless"} for an item {@code "Paperless ngx Admin (example.invalid)"}.</p>
 *
 * <p><b>Fail-safe:</b> if the client is disabled or an error occurs, all getters
 * return {@link Optional#empty()} — an exception is NEVER passed to the outside
 * and the app boot is NEVER broken. Secret values are not logged (only item
 * names/booleans).</p>
 *
 * <p><b>Rotation</b> ({@link #rotatePassword(String, String)}): sets a new password
 * on an item (write direction, {@code PUT /api/ciphers/{id}}), leaves all other
 * fields unchanged and invalidates the read cache afterwards. Fail-safe as well:
 * {@code false} when the vault is disabled or on any error, never an exception to
 * the outside.</p>
 */
@Slf4j
public class VaultwardenSecretService {

    private final VaultwardenProperties props;
    private final VaultwardenClient client;

    public VaultwardenSecretService(VaultwardenProperties props, VaultwardenClient client) {
        this.props = props;
        this.client = client;
    }

    /** {@code true} when the vault client is active by configuration. */
    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** Password of the named item. */
    public Optional<String> getPassword(String itemName) {
        return findItem(itemName).map(VaultwardenItem::password).filter(v -> v != null && !v.isEmpty());
    }

    /** User name of the named item. */
    public Optional<String> getUsername(String itemName) {
        return findItem(itemName).map(VaultwardenItem::username).filter(v -> v != null && !v.isEmpty());
    }

    /** Custom field of the named item (field name case-insensitive). */
    public Optional<String> getField(String itemName, String fieldName) {
        return findItem(itemName).flatMap(i -> i.field(fieldName)).filter(v -> v != null && !v.isEmpty());
    }

    /** The entire decrypted item (username/password/fields). */
    public Optional<VaultwardenItem> getSecret(String itemName) {
        return findItem(itemName);
    }

    /**
     * Rotates (overwrites) the password of the named login item in the vault.
     *
     * <p>Only {@code login.password} is re-encrypted and written; all other fields
     * stay unchanged. After a successful write the read cache is invalidated, so that
     * subsequent {@link #getPassword(String)} calls return the new value.</p>
     *
     * <p>Fail-safe: {@code false} when the vault is disabled, the inputs are missing
     * or an error occurs — an exception is NEVER thrown and a secret is NEVER
     * logged.</p>
     *
     * @param itemName    item name (matched as in the getters: exact, then contains)
     * @param newPassword new password in plaintext
     * @return {@code true} on a successful write (2xx), otherwise {@code false}
     */
    public boolean rotatePassword(String itemName, String newPassword) {
        if (!isEnabled()) {
            log.debug("Vault deaktiviert - Rotation von Item '{}' uebersprungen", itemName);
            return false;
        }
        if (itemName == null || itemName.isBlank() || newPassword == null) {
            return false;
        }
        try {
            return client.rotatePassword(itemName, newPassword);
        } catch (Exception e) {
            // fail-safe: never throw to the outside
            log.warn("Rotation fuer Item '{}' fehlgeschlagen: {}", itemName, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Boot retry support (package-private, only for the VaultwardenValueResolver)
    // ------------------------------------------------------------------

    /**
     * {@code true} when the last vault access failed because of a TRANSIENT disturbance
     * (login/sync error, timeout, HTTP 429) — the empty answer then does NOT prove that the
     * item is missing. {@code false} after a successful sync: an empty answer is definitive then.
     */
    boolean istLetzterZugriffTransientGescheitert() {
        return client.istLetzterRefreshGescheitert();
    }

    /** {@code true} when the last failure was a recognized rate limit (HTTP 429). */
    boolean warLetzterFehlerRateLimit() {
        return client.warLetzterFehlerRateLimit();
    }

    /** Secret-free message of the last failure; empty after a success. */
    String letzteVaultFehlermeldung() {
        return client.letzteFehlermeldung();
    }

    /**
     * Discards the (possibly empty) cache together with the error backoff, so that the NEXT
     * access really goes to Vaultwarden. For the boot retry in the resolver, which controls the
     * waiting time itself.
     */
    void erzwingeNeuenVersuch() {
        client.invalidate();
    }

    // ------------------------------------------------------------------

    private Optional<VaultwardenItem> findItem(String itemName) {
        if (!isEnabled()) {
            log.debug("Vault deaktiviert - kein Zugriff auf Item '{}'", itemName);
            return Optional.empty();
        }
        if (itemName == null || itemName.isBlank()) {
            return Optional.empty();
        }
        try {
            List<VaultwardenItem> items = client.getItems();
            String needle = itemName.trim();
            // 1) exact name (case-insensitive)
            for (VaultwardenItem i : items) {
                if (i.name() != null && i.name().equalsIgnoreCase(needle)) {
                    return Optional.of(i);
                }
            }
            // 2) contains (case-insensitive)
            String needleLc = needle.toLowerCase(Locale.ROOT);
            for (VaultwardenItem i : items) {
                if (i.name() != null && i.name().toLowerCase(Locale.ROOT).contains(needleLc)) {
                    return Optional.of(i);
                }
            }
            log.debug("Kein Vault-Item passend zu '{}' ({} Items geprueft)", itemName, items.size());
            return Optional.empty();
        } catch (Exception e) {
            // fail-safe: never throw to the outside
            log.warn("Vault-Zugriff fuer Item '{}' fehlgeschlagen: {}", itemName, e.getMessage());
            return Optional.empty();
        }
    }
}

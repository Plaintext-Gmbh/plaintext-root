/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.boot.plugins.secret.VaultwardenSecretService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Backend via the existing Vaultwarden integration (root-common). Does NOT change the boot property
 * injection — only uses the existing {@link VaultwardenSecretService} API (rotatePassword = set,
 * getSecret = comment). The service bean exists only when the vault is active → {@link ObjectProvider}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VaultwardenSecretBackend implements SecretBackend {

    private final ObjectProvider<VaultwardenSecretService> vaultProvider;

    @Override
    public SecretBackendType type() {
        return SecretBackendType.VAULTWARDEN;
    }

    @Override
    public boolean isAvailable() {
        VaultwardenSecretService v = vaultProvider.getIfAvailable();
        return v != null && v.isEnabled();
    }

    @Override
    public SecretHealth health() {
        VaultwardenSecretService v = vaultProvider.getIfAvailable();
        if (v == null) {
            return SecretHealth.down("Vaultwarden-Integration nicht aktiv (Bean fehlt) — "
                    + "PLAINTEXT_VAULT_ENABLED=true sowie EMAIL/MASTER_PASSWORD per Env setzen.");
        }
        if (!v.isEnabled()) {
            return SecretHealth.down("Vaultwarden ist deaktiviert oder nicht erreichbar — "
                    + "PLAINTEXT_VAULT_ENABLED/EMAIL/MASTER_PASSWORD prüfen.");
        }
        return SecretHealth.up("Vaultwarden greift.");
    }

    @Override
    public String readValue(String name) {
        VaultwardenSecretService v = vaultProvider.getIfAvailable();
        return v == null ? null : v.getPassword(name).orElse(null);
    }

    @Override
    public String comment(String name) {
        VaultwardenSecretService v = vaultProvider.getIfAvailable();
        if (v == null) {
            return null;
        }
        // Vaultwarden "Notes" or a custom field "comment" (best effort — no value is read).
        return v.getField(name, "notes").orElseGet(() -> v.getField(name, "comment").orElse(null));
    }

    @Override
    public void set(String name, String value, String note) {
        VaultwardenSecretService v = vaultProvider.getIfAvailable();
        if (v == null || !v.isEnabled()) {
            throw new IllegalStateException("Vaultwarden-Backend nicht verfuegbar/aktiviert");
        }
        if (!v.rotatePassword(name, value)) {
            throw new IllegalStateException("Vaultwarden-Item '" + name + "' konnte nicht gesetzt werden "
                    + "(existiert es als Login-Item?)");
        }
    }
}

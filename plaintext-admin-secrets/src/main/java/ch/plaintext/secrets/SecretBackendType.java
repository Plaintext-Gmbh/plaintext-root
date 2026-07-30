/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

/**
 * Backend, in dem Secrets liegen. {@link #VAULTWARDEN} = bestehende Vaultwarden-Integration
 * (root-common), {@link #LOCAL_DB} = AES-GCM-verschlüsselt in der App-DB, {@link #HASHICORP} =
 * HashiCorp Vault (Phase 4).
 */
public enum SecretBackendType {
    VAULTWARDEN,
    LOCAL_DB,
    HASHICORP
}

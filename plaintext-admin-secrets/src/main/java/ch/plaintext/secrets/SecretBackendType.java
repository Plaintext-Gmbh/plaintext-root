/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

/**
 * Backend in which secrets are stored. {@link #VAULTWARDEN} = existing Vaultwarden integration
 * (root-common), {@link #LOCAL_DB} = AES-GCM-encrypted in the app DB, {@link #HASHICORP} =
 * HashiCorp Vault (phase 4).
 */
public enum SecretBackendType {
    VAULTWARDEN,
    LOCAL_DB,
    HASHICORP
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Konfiguration des aktiven Secret-Backends (pro Mandant). {@link #configEncrypted} hält das
 * AES-GCM-verschlüsselte Zugriffstoken/-JSON (Key = Env {@code PLAINTEXT_SECRET_KEY}).
 */
@Entity
@Table(name = "secret_backend_config")
@Data
@EqualsAndHashCode(callSuper = true)
public class SecretBackendConfig extends SuperModel {

    @Enumerated(EnumType.STRING)
    @Column(name = "backend_type", length = 32, nullable = false)
    private SecretBackendType backendType;

    /** AES-GCM base64: Backend-Zugriffstoken/URL/JSON. NIE im UI anzeigen. */
    @Column(name = "config_encrypted", length = 8000)
    private String configEncrypted;

    @Column(name = "aktiv", nullable = false)
    private boolean aktiv;
}

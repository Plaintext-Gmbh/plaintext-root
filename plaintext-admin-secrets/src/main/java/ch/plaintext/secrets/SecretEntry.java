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
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A managed secret. The VALUE is never held in plaintext:
 * {@link SecretBackendType#LOCAL_DB} → {@link #wertEncrypted} (AES-GCM), otherwise the value lives in
 * the external backend and only metadata is kept here (name, note, {@code createdDate} = first entry).
 * {@code createdDate}/{@code mandat}/{@code deleted} come from {@link SuperModel}.
 */
@Entity
@Table(name = "secret_entry")
@Data
@EqualsAndHashCode(callSuper = true)
public class SecretEntry extends SuperModel {

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "backend_type", length = 32, nullable = false)
    private SecretBackendType backendType;

    @Column(name = "note", length = 2000)
    private String note;

    /** LOCAL_DB only: AES-GCM base64(iv||ct||tag). NEVER display in the UI (one-way). */
    @Column(name = "wert_encrypted", length = 8000)
    private String wertEncrypted;

    /** Comment loaded live from the backend (Vaultwarden) — not persisted. */
    @Transient
    private String comment;
}

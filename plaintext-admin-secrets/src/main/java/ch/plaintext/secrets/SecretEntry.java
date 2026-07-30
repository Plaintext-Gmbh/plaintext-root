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
 * Ein verwaltetes Secret. Der WERT wird nie im Klartext gehalten:
 * {@link SecretBackendType#LOCAL_DB} → {@link #wertEncrypted} (AES-GCM), sonst lebt der Wert im
 * externen Backend und hier stehen nur Metadaten (Name, Notiz, {@code createdDate} = Neueintragung).
 * {@code createdDate}/{@code mandat}/{@code deleted} kommen aus {@link SuperModel}.
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

    /** Nur LOCAL_DB: AES-GCM base64(iv||ct||tag). NIE im UI anzeigen (one-way). */
    @Column(name = "wert_encrypted", length = 8000)
    private String wertEncrypted;

    /** Live aus dem Backend geladener Kommentar (Vaultwarden) — nicht persistiert. */
    @Transient
    private String comment;
}

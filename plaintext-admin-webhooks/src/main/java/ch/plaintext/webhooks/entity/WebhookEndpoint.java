/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.entity;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Admin-verwalteter Ziel-Endpoint für ausgehende Webhooks (mandantengescoped). {@link #eventTypes}
 * ist eine Komma-Liste der abonnierten {@code PlaintextDomainEvent#eventType()}-Werte (z. B.
 * {@code rechnung.created,rechnung.status_changed}). Das Signing-Secret liegt AES-256-GCM
 * verschlüsselt vor ({@link ch.plaintext.webhooks.service.WebhookCrypto}) — es wird bei der Anlage
 * einmalig im Klartext angezeigt und danach nie wieder ausgelesen.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Entity
@Table(name = "webhook_endpoint")
@Data
@EqualsAndHashCode(callSuper = false)
public class WebhookEndpoint extends SuperModel {

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "url", length = 1000, nullable = false)
    private String url;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "event_types", length = 2000, nullable = false)
    private String eventTypes;

    /** AES-256-GCM base64(iv||ct||tag) — nie im Klartext persistiert. */
    @Column(name = "signing_secret_encrypted", length = 2000, nullable = false)
    private String signingSecretEncrypted;
}

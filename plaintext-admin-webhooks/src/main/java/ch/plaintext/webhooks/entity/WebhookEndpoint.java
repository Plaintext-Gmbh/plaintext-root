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
 * Admin-managed target endpoint for outgoing webhooks (tenant-scoped). {@link #eventTypes} is a
 * comma-separated list of the subscribed {@code PlaintextDomainEvent#eventType()} values (e.g.
 * {@code rechnung.created,rechnung.status_changed}). The signing secret is stored AES-256-GCM
 * encrypted ({@link ch.plaintext.webhooks.service.WebhookCrypto}) — it is shown in plain text once
 * when the endpoint is created and never read out again afterwards.
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

    /** AES-256-GCM base64(iv||ct||tag) — never persisted in plain text. */
    @Column(name = "signing_secret_encrypted", length = 2000, nullable = false)
    private String signingSecretEncrypted;
}

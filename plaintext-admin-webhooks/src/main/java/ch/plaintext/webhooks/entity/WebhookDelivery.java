/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.entity;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * One delivery attempt (or rather a series of retry attempts) of a {@link WebhookEndpoint} for a
 * specific domain event — delivery log + basis for the retry cron.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Entity
@Table(name = "webhook_delivery")
@Data
@EqualsAndHashCode(callSuper = false)
public class WebhookDelivery extends SuperModel {

    @Column(name = "endpoint_id", nullable = false)
    private Long endpointId;

    @Column(name = "event_type", length = 200, nullable = false)
    private String eventType;

    @Column(name = "payload", length = 8000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_snippet", length = 2000)
    private String responseSnippet;
}

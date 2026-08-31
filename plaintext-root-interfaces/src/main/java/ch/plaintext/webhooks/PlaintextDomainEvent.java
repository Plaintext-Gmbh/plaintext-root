/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks;

import java.util.Map;

/**
 * Generic domain event for the webhook dispatcher ({@code plaintext-admin-webhooks}). Business
 * modules (root/app/guild) publish it via {@code ApplicationEventPublisher.publishEvent(...)} — no
 * cross-module call needed; the dispatcher listens as a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, provided the module
 * {@code plaintext-admin-webhooks} is on the classpath (otherwise nothing happens at all).
 *
 * @param eventType  e.g. {@code rechnung.created}, {@code member.created}
 * @param entityType e.g. {@code Rechnung}
 * @param entityId   ID of the affected entity as a string
 * @param mandant    tenant whose webhook endpoints are notified
 * @param payload    core fields for the recipient (keep it deliberately slim, no dumps of personal data)
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public record PlaintextDomainEvent(String eventType, String entityType, String entityId, String mandant,
                                   Map<String, Object> payload) {
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks;

import java.util.Map;

/**
 * Generisches Domain-Event für den Webhook-Dispatcher ({@code plaintext-admin-webhooks}). Fachliche
 * Module (root/app/guild) veröffentlichen es via {@code ApplicationEventPublisher.publishEvent(...)}
 * — kein Cross-Modul-Aufruf nötig, der Dispatcher hört als
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} zu, sofern das Modul
 * {@code plaintext-admin-webhooks} auf dem Klassenpfad ist (sonst passiert einfach nichts).
 *
 * @param eventType  z. B. {@code rechnung.created}, {@code member.created}
 * @param entityType z. B. {@code Rechnung}
 * @param entityId   ID der betroffenen Entity als String
 * @param mandant    Mandant, dessen Webhook-Endpoints benachrichtigt werden
 * @param payload    Kernfelder für den Empfänger (bewusst schlank halten, keine Personendaten-Dumps)
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public record PlaintextDomainEvent(String eventType, String entityType, String entityId, String mandant,
                                   Map<String, Object> payload) {
}

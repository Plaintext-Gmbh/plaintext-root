/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.entity;

/** Zustand eines {@link WebhookDelivery}-Versuchs. */
public enum WebhookDeliveryStatus {
    /** Noch kein (weiterer) Versandversuch unternommen bzw. fällig für Retry. */
    PENDING,
    /** Erfolgreich zugestellt (2xx-Antwort). */
    OK,
    /** Zustellung fehlgeschlagen, Retry ist noch möglich ({@link WebhookDelivery#getNextAttemptAt()}). */
    FAILED,
    /** Maximale Anzahl Versuche erreicht — kein weiterer Retry. */
    GIVEN_UP
}

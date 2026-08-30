/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.entity;

/** State of a {@link WebhookDelivery} attempt. */
public enum WebhookDeliveryStatus {
    /** No (further) send attempt made yet, or due for a retry. */
    PENDING,
    /** Delivered successfully (2xx response). */
    OK,
    /** Delivery failed, a retry is still possible ({@link WebhookDelivery#getNextAttemptAt()}). */
    FAILED,
    /** Maximum number of attempts reached — no further retry. */
    GIVEN_UP
}

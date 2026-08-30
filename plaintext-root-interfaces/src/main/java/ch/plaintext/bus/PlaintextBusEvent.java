/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

import java.time.Instant;

/**
 * Envelope in which {@link PlaintextEventBus} publishes a typed payload (typically a model
 * interface from a {@code *-interfaces} module, e.g. {@code IRechnung}, {@code IncomingMail}) onto
 * the internal bus. The context ({@code mandant}/{@code userId}) is captured from
 * {@code PlaintextSecurityHolder} at publish time and read from the envelope ALWAYS thereafter —
 * never from the subscriber's thread (which may differ from the publisher's).
 *
 * @param payload  the actual event object
 * @param scope    delivery scope (see {@link ExecutionScope})
 * @param mandant  tenant of the event, or {@code null} for {@link ExecutionScope#APPLICATION}
 * @param userId   triggering user, set only for {@link ExecutionScope#PERSOENLICH}
 * @param at       time of the publish
 * @param <T>      type of the payload
 * @author info@plaintext.ch
 * @since 2026
 */
public record PlaintextBusEvent<T>(T payload, ExecutionScope scope, String mandant, String userId, Instant at) {
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

/**
 * Subscriber to an event type on the internal bus. All Spring beans of this type are collected
 * automatically by the dispatcher (standard collection injection, no classpath scan needed —
 * analogous to {@code PlaintextCron}/{@code CronController}, but without its bean-wrapping
 * ceremony, since no per-tenant state is held in the subscriber itself here).
 *
 * @param <T> type of the event this subscriber listens for
 * @author info@plaintext.ch
 * @since 2026
 */
public interface PlaintextBusSubscriber<T> {

    /** The event type this subscriber listens for (exact class, no subtype detection). */
    Class<T> eventType();

    /**
     * Delivery scope of this subscriber — determines which {@link PlaintextBusEvent#scope()}
     * values are delivered (see {@link PlaintextEventBus} for the exact matrix). Defaults to
     * {@link ExecutionScope#MANDAT} (the most common case: business modules react per tenant).
     */
    default ExecutionScope scope() {
        return ExecutionScope.MANDAT;
    }

    /**
     * Called for every delivered event. Before the call the dispatcher has already set a
     * {@code SecurityContext} matching the event (tenant, and for
     * {@link ExecutionScope#PERSOENLICH} the user as well) — implementations may use
     * {@code PlaintextSecurityHolder} as usual.
     *
     * @param payload the event object
     * @param ctx     the full envelope (for metadata such as {@code at}, if needed)
     */
    void onEvent(T payload, PlaintextBusEvent<T> ctx);
}

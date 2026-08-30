/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Publish side of the internal event bus: wraps a payload (typically a model interface from a
 * {@code *-interfaces} module) into a {@link PlaintextBusEvent} and publishes it via the
 * {@link ApplicationEventPublisher} — {@link PlaintextBusDispatcher} takes care of the delivery
 * to matching {@link PlaintextBusSubscriber}s. Tenant/user are captured automatically from
 * {@link PlaintextSecurityHolder}, not passed in by the caller — analogous to the way
 * {@code SuperCron} derives the execution context implicitly from the running cron job.
 *
 * <p><b>At-most-once, in-memory, after commit</b> — no persistence, no retry, no ordering
 * guarantee. Whoever needs guaranteed delivery uses the delivery-log + cron pattern (like
 * {@code plaintext-admin-webhooks}) behind a subscriber.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Service
@RequiredArgsConstructor
public class PlaintextEventBus {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Publishes {@code payload} on the bus. With {@link ExecutionScope#APPLICATION} no tenant is
     * captured (tenant-less); with {@link ExecutionScope#PERSOENLICH} additionally the current
     * user.
     *
     * @param payload the event object
     * @param scope   delivery scope
     * @param <T>     type of the payload
     */
    public <T> void publish(T payload, ExecutionScope scope) {
        String mandant = scope == ExecutionScope.APPLICATION ? null : PlaintextSecurityHolder.getMandat();
        String userId = scope == ExecutionScope.PERSOENLICH ? PlaintextSecurityHolder.getUser() : null;
        eventPublisher.publishEvent(new PlaintextBusEvent<>(payload, scope, mandant, userId, Instant.now()));
    }
}

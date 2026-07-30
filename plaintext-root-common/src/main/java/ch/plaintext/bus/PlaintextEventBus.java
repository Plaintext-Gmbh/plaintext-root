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
 * Publish-Seite des internen Event-Bus: verpackt ein Payload (typischerweise ein Model-Interface aus
 * einem {@code *-interfaces}-Modul) in einen {@link PlaintextBusEvent} und veröffentlicht ihn via
 * {@link ApplicationEventPublisher} — {@link PlaintextBusDispatcher} übernimmt Zustellung an
 * passende {@link PlaintextBusSubscriber}. Mandant/Benutzer werden automatisch aus
 * {@link PlaintextSecurityHolder} erfasst, nicht vom Aufrufer übergeben — analog zu, wie
 * {@code SuperCron} den Ausführungs-Kontext implizit aus dem laufenden Cron-Job ableitet.
 *
 * <p><b>At-most-once, in-memory, nach Commit</b> — keine Persistenz, kein Retry, keine
 * Reihenfolge-Garantie. Wer garantierte Zustellung braucht, nutzt das Delivery-Log+Cron-Muster
 * (wie {@code plaintext-admin-webhooks}) hinter einem Subscriber.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Service
@RequiredArgsConstructor
public class PlaintextEventBus {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Veröffentlicht {@code payload} auf dem Bus. Bei {@link ExecutionScope#APPLICATION} wird kein
     * Mandant erfasst (mandantenlos); bei {@link ExecutionScope#PERSOENLICH} zusätzlich der aktuelle
     * Benutzer.
     *
     * @param payload das Event-Objekt
     * @param scope   Zustellungs-Scope
     * @param <T>     Typ des Payloads
     */
    public <T> void publish(T payload, ExecutionScope scope) {
        String mandant = scope == ExecutionScope.APPLICATION ? null : PlaintextSecurityHolder.getMandat();
        String userId = scope == ExecutionScope.PERSOENLICH ? PlaintextSecurityHolder.getUser() : null;
        eventPublisher.publishEvent(new PlaintextBusEvent<>(payload, scope, mandant, userId, Instant.now()));
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Zentraler Zustell-Punkt des internen Event-Bus. Sammelt alle {@link PlaintextBusSubscriber}-Beans
 * (Standard-Collection-Injection) und verteilt jedes {@link PlaintextBusEvent} nach Commit an alle
 * passenden Subscriber (Typ- und Scope-Match, siehe {@link #passtScope}).
 *
 * <p><b>Kontext:</b> vor jedem {@code onEvent}-Aufruf wird ein {@link SecurityContext} nach dem
 * Muster von {@code SuperCron.loginMandat} gesetzt (Mandant aus dem Event, bei
 * {@link ExecutionScope#PERSOENLICH} zusätzlich der Benutzer) und danach zwingend zurückgesetzt
 * (finally) — wichtig, da der dedizierte Bus-Thread-Pool Threads über mehrere Events hinweg
 * wiederverwendet und sonst ein Kontext-Leck zwischen unabhängigen Events entstünde.</p>
 *
 * <p><b>Fehler-Isolation:</b> je Subscriber try/catch + {@code log.warn} — ein kaputter Subscriber
 * darf weder andere Subscriber noch den Publisher stören (Best-effort-Philosophie wie
 * {@code DestructiveActionAuditService}/{@code WebhookDispatchService}).</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlaintextBusDispatcher {

    private static final String ROLE_SYSTEM = "ROLE_SYSTEM";
    private static final String SYSTEM_USER = "SYSTEM";

    private final List<PlaintextBusSubscriber<?>> subscribers;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async(PlaintextBusConfig.EXECUTOR_BEAN_NAME)
    public void onBusEvent(PlaintextBusEvent<?> envelope) {
        for (PlaintextBusSubscriber<?> subscriber : subscribers) {
            if (!subscriber.eventType().isInstance(envelope.payload())) {
                continue;
            }
            if (!passtScope(subscriber.scope(), envelope.scope())) {
                continue;
            }
            zustellen(subscriber, envelope);
        }
    }

    /**
     * Zustellregel (= Cron-Semantik): MANDAT-Subscriber erhalten MANDAT- UND PERSOENLICH-Events
     * (Kontext kommt aus dem Event); APPLICATION-Subscriber nur APPLICATION-Events;
     * PERSOENLICH-Subscriber nur PERSOENLICH-Events. Keine Cross-Scope-Zustellung darüber hinaus.
     */
    private boolean passtScope(ExecutionScope subscriberScope, ExecutionScope eventScope) {
        return switch (subscriberScope) {
            case APPLICATION -> eventScope == ExecutionScope.APPLICATION;
            case MANDAT -> eventScope == ExecutionScope.MANDAT || eventScope == ExecutionScope.PERSOENLICH;
            case PERSOENLICH -> eventScope == ExecutionScope.PERSOENLICH;
        };
    }

    @SuppressWarnings("unchecked")
    private void zustellen(PlaintextBusSubscriber<?> subscriber, PlaintextBusEvent<?> envelope) {
        SecurityContext vorheriger = SecurityContextHolder.getContext();
        boolean vorherigerHatteAuthentication = vorheriger.getAuthentication() != null;
        loginContext(envelope);
        try {
            PlaintextBusSubscriber<Object> typed = (PlaintextBusSubscriber<Object>) subscriber;
            PlaintextBusEvent<Object> typedEnvelope = (PlaintextBusEvent<Object>) envelope;
            typed.onEvent(envelope.payload(), typedEnvelope);
        } catch (Exception e) {
            log.warn("Bus-Subscriber {} fehlgeschlagen fuer Event {}: {}",
                    subscriber.getClass().getName(), envelope.payload().getClass().getSimpleName(), e.toString());
        } finally {
            if (vorherigerHatteAuthentication) {
                SecurityContextHolder.setContext(vorheriger);
            } else {
                SecurityContextHolder.clearContext();
            }
        }
    }

    /** Setzt einen minimalen SecurityContext aus dem Event-Kontext, analog {@code SuperCron.loginMandat}. */
    private void loginContext(PlaintextBusEvent<?> envelope) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority(ROLE_SYSTEM));
        String mandant = envelope.mandant();
        if (mandant != null && !mandant.isBlank()) {
            authorities.add(new SimpleGrantedAuthority("PROPERTY_MANDAT_" + mandant.toLowerCase(Locale.ROOT)));
        }
        String principal = envelope.userId() != null && !envelope.userId().isBlank() ? envelope.userId() : SYSTEM_USER;
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }
}

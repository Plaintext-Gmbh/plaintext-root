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
 * Central delivery point of the internal event bus. Collects all {@link PlaintextBusSubscriber}
 * beans (standard collection injection) and distributes every {@link PlaintextBusEvent} after
 * commit to all matching subscribers (type and scope match, see {@link #passtScope}).
 *
 * <p><b>Context:</b> before every {@code onEvent} call a {@link SecurityContext} is set following
 * the pattern of {@code SuperCron.loginMandat} (tenant from the event, with
 * {@link ExecutionScope#PERSOENLICH} additionally the user) and is reset afterwards without fail
 * (finally) — important, because the dedicated bus thread pool reuses threads across several
 * events and a context leak between independent events would arise otherwise.</p>
 *
 * <p><b>Error isolation:</b> try/catch + {@code log.warn} per subscriber — a broken subscriber
 * must disturb neither the other subscribers nor the publisher (the best-effort philosophy of
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
     * Delivery rule (= cron semantics): MANDAT subscribers receive MANDAT AND PERSOENLICH events
     * (the context comes from the event); APPLICATION subscribers only APPLICATION events;
     * PERSOENLICH subscribers only PERSOENLICH events. No cross-scope delivery beyond that.
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

    /** Sets a minimal SecurityContext from the event context, analogous to {@code SuperCron.loginMandat}. */
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

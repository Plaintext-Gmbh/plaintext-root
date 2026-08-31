/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PlaintextBusDispatcher}: type/scope delivery matrix, setting/resetting the
 * context, error isolation (Task 004). Calls {@code onBusEvent} directly (like
 * {@code WebhookDispatchServiceTest} — no Spring test context, the
 * {@code @TransactionalEventListener}/{@code @Async} semantics themselves are not covered,
 * only the business logic of the method).
 */
class PlaintextBusDispatcherTest {

    private record Foo(String wert) {
    }

    private record Bar(String wert) {
    }

    private static class RecordingSubscriber implements PlaintextBusSubscriber<Foo> {
        private final ExecutionScope scope;
        final List<PlaintextBusEvent<Foo>> empfangen = new ArrayList<>();
        Authentication kontextBeimEmpfang;

        RecordingSubscriber(ExecutionScope scope) {
            this.scope = scope;
        }

        @Override
        public Class<Foo> eventType() {
            return Foo.class;
        }

        @Override
        public ExecutionScope scope() {
            return scope;
        }

        @Override
        public void onEvent(Foo payload, PlaintextBusEvent<Foo> ctx) {
            empfangen.add(ctx);
            kontextBeimEmpfang = SecurityContextHolder.getContext().getAuthentication();
        }
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private PlaintextBusEvent<Foo> event(ExecutionScope scope, String mandant, String userId) {
        return new PlaintextBusEvent<>(new Foo("x"), scope, mandant, userId, Instant.now());
    }

    // ── Type match ───────────────────────────────────────────

    @Test
    void onBusEvent_falscherPayloadTyp_wirdNichtZugestellt() {
        RecordingSubscriber sub = new RecordingSubscriber(ExecutionScope.MANDAT);
        PlaintextBusSubscriber<Bar> barSub = new PlaintextBusSubscriber<>() {
            public Class<Bar> eventType() {
                return Bar.class;
            }

            public void onEvent(Bar payload, PlaintextBusEvent<Bar> ctx) {
                throw new AssertionError("darf nicht aufgerufen werden");
            }
        };
        PlaintextBusDispatcher dispatcher = new PlaintextBusDispatcher(List.of(sub, barSub));

        dispatcher.onBusEvent(event(ExecutionScope.MANDAT, "m1", null));

        assertThat(sub.empfangen).hasSize(1);
    }

    // ── Scope delivery matrix ────────────────────────────────

    @Test
    void scopeMatrix_applicationSubscriber_nurApplicationEvents() {
        RecordingSubscriber sub = new RecordingSubscriber(ExecutionScope.APPLICATION);
        PlaintextBusDispatcher dispatcher = new PlaintextBusDispatcher(List.of(sub));

        dispatcher.onBusEvent(event(ExecutionScope.APPLICATION, null, null));
        dispatcher.onBusEvent(event(ExecutionScope.MANDAT, "m1", null));
        dispatcher.onBusEvent(event(ExecutionScope.PERSOENLICH, "m1", "u1"));

        assertThat(sub.empfangen).hasSize(1);
        assertThat(sub.empfangen.get(0).scope()).isEqualTo(ExecutionScope.APPLICATION);
    }

    @Test
    void scopeMatrix_mandatSubscriber_erhaeltMandatUndPersoenlich() {
        RecordingSubscriber sub = new RecordingSubscriber(ExecutionScope.MANDAT);
        PlaintextBusDispatcher dispatcher = new PlaintextBusDispatcher(List.of(sub));

        dispatcher.onBusEvent(event(ExecutionScope.APPLICATION, null, null));
        dispatcher.onBusEvent(event(ExecutionScope.MANDAT, "m1", null));
        dispatcher.onBusEvent(event(ExecutionScope.PERSOENLICH, "m1", "u1"));

        assertThat(sub.empfangen).hasSize(2);
        assertThat(sub.empfangen).extracting(PlaintextBusEvent::scope)
                .containsExactly(ExecutionScope.MANDAT, ExecutionScope.PERSOENLICH);
    }

    @Test
    void scopeMatrix_persoenlichSubscriber_nurPersoenlicheEvents() {
        RecordingSubscriber sub = new RecordingSubscriber(ExecutionScope.PERSOENLICH);
        PlaintextBusDispatcher dispatcher = new PlaintextBusDispatcher(List.of(sub));

        dispatcher.onBusEvent(event(ExecutionScope.APPLICATION, null, null));
        dispatcher.onBusEvent(event(ExecutionScope.MANDAT, "m1", null));
        dispatcher.onBusEvent(event(ExecutionScope.PERSOENLICH, "m1", "u1"));

        assertThat(sub.empfangen).hasSize(1);
        assertThat(sub.empfangen.get(0).scope()).isEqualTo(ExecutionScope.PERSOENLICH);
    }

    // ── Setting/resetting the context ────────────────────────

    @Test
    void onEvent_kontextEnthaeltMandantAlsAuthority() {
        RecordingSubscriber sub = new RecordingSubscriber(ExecutionScope.MANDAT);
        PlaintextBusDispatcher dispatcher = new PlaintextBusDispatcher(List.of(sub));

        dispatcher.onBusEvent(event(ExecutionScope.MANDAT, "m1", null));

        assertThat(sub.kontextBeimEmpfang).isNotNull();
        assertThat(sub.kontextBeimEmpfang.getAuthorities())
                .extracting(Object::toString).contains("PROPERTY_MANDAT_m1");
        assertThat(sub.kontextBeimEmpfang.getName()).isEqualTo("SYSTEM");
    }

    @Test
    void onEvent_persoenlich_principalIstDerEventBenutzer() {
        RecordingSubscriber sub = new RecordingSubscriber(ExecutionScope.PERSOENLICH);
        PlaintextBusDispatcher dispatcher = new PlaintextBusDispatcher(List.of(sub));

        dispatcher.onBusEvent(event(ExecutionScope.PERSOENLICH, "m1", "daniel"));

        assertThat(sub.kontextBeimEmpfang.getName()).isEqualTo("daniel");
    }

    @Test
    void onEvent_stelltVorherigenSecurityContextWiederHer() {
        Authentication vorherige = new UsernamePasswordAuthenticationToken("vorheriger-user", null, List.of());
        SecurityContext vorherigerContext = SecurityContextHolder.createEmptyContext();
        vorherigerContext.setAuthentication(vorherige);
        SecurityContextHolder.setContext(vorherigerContext);

        RecordingSubscriber sub = new RecordingSubscriber(ExecutionScope.MANDAT);
        PlaintextBusDispatcher dispatcher = new PlaintextBusDispatcher(List.of(sub));

        dispatcher.onBusEvent(event(ExecutionScope.MANDAT, "m1", null));

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("vorheriger-user");
    }

    @Test
    void onEvent_ohneVorherigenKontext_wirdAmEndeGecleared() {
        SecurityContextHolder.clearContext();
        RecordingSubscriber sub = new RecordingSubscriber(ExecutionScope.MANDAT);
        PlaintextBusDispatcher dispatcher = new PlaintextBusDispatcher(List.of(sub));

        dispatcher.onBusEvent(event(ExecutionScope.MANDAT, "m1", null));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ── Error isolation ──────────────────────────────────────

    @Test
    void onEvent_einSubscriberWirftException_andereWerdenTrotzdemAufgerufen() {
        RecordingSubscriber funktioniert = new RecordingSubscriber(ExecutionScope.MANDAT);
        PlaintextBusSubscriber<Foo> kaputt = new PlaintextBusSubscriber<>() {
            public Class<Foo> eventType() {
                return Foo.class;
            }

            public void onEvent(Foo payload, PlaintextBusEvent<Foo> ctx) {
                throw new RuntimeException("boom");
            }
        };
        PlaintextBusDispatcher dispatcher = new PlaintextBusDispatcher(List.of(kaputt, funktioniert));

        dispatcher.onBusEvent(event(ExecutionScope.MANDAT, "m1", null));

        assertThat(funktioniert.empfangen).hasSize(1);
    }
}

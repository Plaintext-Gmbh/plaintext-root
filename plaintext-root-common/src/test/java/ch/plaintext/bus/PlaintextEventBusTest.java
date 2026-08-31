/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Tests for {@link PlaintextEventBus}: a correctly filled envelope per scope (Task 004). */
class PlaintextEventBusTest {

    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final PlaintextEventBus bus = new PlaintextEventBus(eventPublisher);

    @Test
    void publish_application_erfasstKeinenMandanten() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mockStatic(PlaintextSecurityHolder.class)) {
            bus.publish("payload", ExecutionScope.APPLICATION);

            ArgumentCaptor<PlaintextBusEvent> captor = ArgumentCaptor.forClass(PlaintextBusEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().payload()).isEqualTo("payload");
            assertThat(captor.getValue().scope()).isEqualTo(ExecutionScope.APPLICATION);
            assertThat(captor.getValue().mandant()).isNull();
            assertThat(captor.getValue().userId()).isNull();
            sec.verify(PlaintextSecurityHolder::getMandat, never());
        }
    }

    @Test
    void publish_mandat_erfasstMandantenAberKeinenBenutzer() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mockStatic(PlaintextSecurityHolder.class)) {
            sec.when(PlaintextSecurityHolder::getMandat).thenReturn("m1");

            bus.publish("payload", ExecutionScope.MANDAT);

            ArgumentCaptor<PlaintextBusEvent> captor = ArgumentCaptor.forClass(PlaintextBusEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().mandant()).isEqualTo("m1");
            assertThat(captor.getValue().userId()).isNull();
        }
    }

    @Test
    void publish_persoenlich_erfasstMandantUndBenutzer() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mockStatic(PlaintextSecurityHolder.class)) {
            sec.when(PlaintextSecurityHolder::getMandat).thenReturn("m1");
            sec.when(PlaintextSecurityHolder::getUser).thenReturn("daniel");

            bus.publish("payload", ExecutionScope.PERSOENLICH);

            ArgumentCaptor<PlaintextBusEvent> captor = ArgumentCaptor.forClass(PlaintextBusEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().mandant()).isEqualTo("m1");
            assertThat(captor.getValue().userId()).isEqualTo("daniel");
        }
    }
}

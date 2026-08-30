/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.service;

import ch.plaintext.webhooks.PlaintextDomainEvent;
import ch.plaintext.webhooks.entity.WebhookEndpoint;
import ch.plaintext.webhooks.repository.WebhookDeliveryRepository;
import ch.plaintext.webhooks.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract test for the transaction boundary of the event listener (Sonar java:S2229, card 891).
 *
 * <p><b>The bug had two layers.</b> First, {@code onDomainEvent} reached the
 * {@code @Transactional} of {@code dispatch} via self-invocation — which bypasses the Spring proxy.
 * Second, and more seriously: the listener runs with {@code AFTER_COMMIT}. There the triggering
 * transaction is still bound to the thread, but is already completed — whoever joins it writes into
 * something that is no longer committed. A mere {@code @Transactional} on the outer method would
 * therefore not be a fix but a startup error: on a {@code @TransactionalEventListener} Spring only
 * permits {@code REQUIRES_NEW} or {@code NOT_SUPPORTED} and aborts the context startup for every
 * other propagation.</p>
 *
 * <p>The test therefore checks not only <em>that</em> a transaction is requested, but <em>with which
 * propagation</em> — exactly the distinction the fix hinges on. It is the safeguard against a later,
 * well-meant unification of the annotations.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class TransaktionsklammerVertragTest {

    private final WebhookEndpointRepository endpointRepo = mock(WebhookEndpointRepository.class);
    private final WebhookDeliveryRepository deliveryRepo = mock(WebhookDeliveryRepository.class);
    private final WebhookCrypto crypto = mock(WebhookCrypto.class);
    private final WebhookHttpClient httpClient = mock(WebhookHttpClient.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    @Configuration
    @EnableTransactionManagement
    static class Konfig {
        @Bean
        WebhookDispatchService webhookDispatchService(WebhookEndpointRepository endpointRepo,
                                                      WebhookDeliveryRepository deliveryRepo,
                                                      WebhookCrypto crypto,
                                                      WebhookHttpClient httpClient) {
            return new WebhookDispatchService(endpointRepo, deliveryRepo, crypto, httpClient);
        }
    }

    private AnnotationConfigApplicationContext kontext() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(WebhookEndpointRepository.class, () -> endpointRepo);
        ctx.registerBean(WebhookDeliveryRepository.class, () -> deliveryRepo);
        ctx.registerBean(WebhookCrypto.class, () -> crypto);
        ctx.registerBean(WebhookHttpClient.class, () -> httpClient);
        ctx.registerBean(PlatformTransactionManager.class, () -> txManager);
        ctx.register(Konfig.class);
        ctx.refresh();
        return ctx;
    }

    private static WebhookEndpoint endpoint(long id) {
        WebhookEndpoint e = new WebhookEndpoint();
        e.setId(id);
        e.setMandat("m1");
        e.setName("Test-Endpoint " + id);
        e.setUrl("https://example.com/hook");
        e.setEnabled(true);
        e.setEventTypes("rechnung.created");
        e.setSigningSecretEncrypted("enc(geheim)");
        return e;
    }

    @Test
    void onDomainEventOeffnetEineEIGENETransaktionFuerAlleEndpoints() {
        when(endpointRepo.findByMandatAndEnabledTrueAndDeletedFalse("m1"))
                .thenReturn(List.of(endpoint(1L), endpoint(2L)));
        when(crypto.decrypt(any())).thenReturn("geheim");
        when(httpClient.post(any(), any(), any()))
                .thenReturn(new WebhookHttpClient.DeliveryResult(true, 200, "ok"));
        when(deliveryRepo.save(any())).thenAnswer(a -> a.getArgument(0));

        try (AnnotationConfigApplicationContext ctx = kontext()) {
            ctx.getBean(WebhookDispatchService.class).onDomainEvent(
                    new PlaintextDomainEvent("rechnung.created", "Rechnung", "42", "m1", Map.of("betrag", "100")));
        }

        // The transaction assertion comes FIRST: it is the subject of the finding. A mistake in the
        // business-level counting below must not leave it unchecked (exactly that happened on the
        // first run of this test — it aborted on the business count before it had measured the
        // bracket).
        ArgumentCaptor<TransactionDefinition> def = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager, times(1)).getTransaction(def.capture());
        assertThat(def.getValue().getPropagationBehavior())
                .as("nach AFTER_COMMIT ist die alte Transaktion abgeschlossen — nur eine EIGENE wird noch committet")
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Both endpoints were served — so the measurement really does reach the business path.
        // Per endpoint dispatch() writes TWICE: once the created delivery, once its result after
        // the delivery attempt. The delivery attempt itself is the more unambiguous number.
        verify(httpClient, times(2)).post(any(), any(), any());
        verify(deliveryRepo, times(4)).save(any());
    }

    @Test
    void ohneAbonnierteEndpointsWirdKeineArbeitAngefangen() {
        // Negative control for the measurement above: if the listener finds nothing to do, the
        // bracket may open (it sits on the method), but no delivery must be created. Without this
        // control it could not be ruled out that the 2 above comes from another source.
        when(endpointRepo.findByMandatAndEnabledTrueAndDeletedFalse("m1")).thenReturn(List.of());

        try (AnnotationConfigApplicationContext ctx = kontext()) {
            ctx.getBean(WebhookDispatchService.class).onDomainEvent(
                    new PlaintextDomainEvent("rechnung.created", "Rechnung", "42", "m1", Map.of("betrag", "100")));
        }

        verify(deliveryRepo, times(0)).save(any());
    }
}

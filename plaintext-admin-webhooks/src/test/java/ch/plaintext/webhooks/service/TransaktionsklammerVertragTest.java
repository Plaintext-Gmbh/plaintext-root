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
 * Vertragstest zur Transaktionsklammer des Event-Listeners (Sonar java:S2229, Karte 891).
 *
 * <p><b>Der Fehler hatte zwei Lagen.</b> Erstens erreichte {@code onDomainEvent} das
 * {@code @Transactional} von {@code dispatch} per Selbstaufruf — der geht am Spring-Proxy vorbei.
 * Zweitens, und schwerer wiegend: der Listener läuft mit {@code AFTER_COMMIT}. Die auslösende
 * Transaktion ist dort zwar noch an den Thread gebunden, aber bereits abgeschlossen — wer sich ihr
 * anschliesst, schreibt in etwas, das nicht mehr committet wird. Ein blosses {@code @Transactional}
 * an der äusseren Methode wäre deshalb keine Behebung, sondern ein Startfehler: Spring lässt an
 * einem {@code @TransactionalEventListener} nur {@code REQUIRES_NEW} oder {@code NOT_SUPPORTED} zu
 * und bricht den Kontextaufbau bei jeder anderen Propagation ab.</p>
 *
 * <p>Der Test prüft daher nicht nur <em>dass</em> eine Transaktion angefordert wird, sondern
 * <em>mit welcher Propagation</em> — genau die Unterscheidung, an der die Behebung hängt. Er ist
 * die Kontrolle gegen ein späteres, gut gemeintes Vereinheitlichen der Annotationen.</p>
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

        // Die Transaktionsaussage steht ZUERST: sie ist der Gegenstand des Befundes. Ein Irrtum in
        // der Fachzählung darunter darf sie nicht ungeprüft lassen (genau das ist beim ersten Lauf
        // dieses Tests passiert — er brach an der Fachzahl ab, bevor er die Klammer gemessen hatte).
        ArgumentCaptor<TransactionDefinition> def = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager, times(1)).getTransaction(def.capture());
        assertThat(def.getValue().getPropagationBehavior())
                .as("nach AFTER_COMMIT ist die alte Transaktion abgeschlossen — nur eine EIGENE wird noch committet")
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Beide Endpoints wurden bedient — die Messung greift also überhaupt auf den Fachweg zu.
        // Je Endpoint schreibt dispatch() ZWEIMAL: einmal die angelegte Delivery, einmal deren
        // Ergebnis nach dem Zustellversuch. Der Zustellversuch selbst ist die eindeutigere Zahl.
        verify(httpClient, times(2)).post(any(), any(), any());
        verify(deliveryRepo, times(4)).save(any());
    }

    @Test
    void ohneAbonnierteEndpointsWirdKeineArbeitAngefangen() {
        // Negativprobe zur Messung oben: findet der Listener nichts zu tun, darf zwar die Klammer
        // aufgehen (sie steht an der Methode), aber keine Delivery entstehen. Ohne diese Probe
        // wäre nicht auszuschliessen, dass die 2 oben aus einer anderen Quelle stammt.
        when(endpointRepo.findByMandatAndEnabledTrueAndDeletedFalse("m1")).thenReturn(List.of());

        try (AnnotationConfigApplicationContext ctx = kontext()) {
            ctx.getBean(WebhookDispatchService.class).onDomainEvent(
                    new PlaintextDomainEvent("rechnung.created", "Rechnung", "42", "m1", Map.of("betrag", "100")));
        }

        verify(deliveryRepo, times(0)).save(any());
    }
}

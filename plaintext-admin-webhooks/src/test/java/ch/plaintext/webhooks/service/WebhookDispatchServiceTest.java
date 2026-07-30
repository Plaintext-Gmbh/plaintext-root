/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.service;

import ch.plaintext.webhooks.PlaintextDomainEvent;
import ch.plaintext.webhooks.entity.WebhookDelivery;
import ch.plaintext.webhooks.entity.WebhookDeliveryStatus;
import ch.plaintext.webhooks.entity.WebhookEndpoint;
import ch.plaintext.webhooks.repository.WebhookDeliveryRepository;
import ch.plaintext.webhooks.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests für {@link WebhookDispatchService}: HMAC-Signatur, Backoff-Progression bis GIVEN_UP,
 * und dass {@link WebhookDispatchService#onDomainEvent} nur abonnierte, aktive Endpoints benachrichtigt.
 */
class WebhookDispatchServiceTest {

    private final WebhookEndpointRepository endpointRepo = mock(WebhookEndpointRepository.class);
    private final WebhookDeliveryRepository deliveryRepo = mock(WebhookDeliveryRepository.class);
    private final WebhookCrypto crypto = mock(WebhookCrypto.class);
    private final WebhookHttpClient httpClient = mock(WebhookHttpClient.class);
    private final WebhookDispatchService service =
            new WebhookDispatchService(endpointRepo, deliveryRepo, crypto, httpClient);

    private static WebhookEndpoint endpoint(long id, String mandat, String eventTypes) {
        WebhookEndpoint e = new WebhookEndpoint();
        e.setId(id);
        e.setMandat(mandat);
        e.setName("Test-Endpoint");
        e.setUrl("https://example.com/hook");
        e.setEnabled(true);
        e.setEventTypes(eventTypes);
        e.setSigningSecretEncrypted("enc(geheim)");
        return e;
    }

    // ── HMAC ─────────────────────────────────────────────────

    @Test
    void hmacSha256_istDeterministischUndAbhaengigVomSecret() {
        String sig1 = WebhookDispatchService.hmacSha256("{\"a\":1}", "geheim");
        String sig2 = WebhookDispatchService.hmacSha256("{\"a\":1}", "geheim");
        String sig3 = WebhookDispatchService.hmacSha256("{\"a\":1}", "anderes-secret");

        assertEquals(sig1, sig2, "gleiche Eingabe -> gleiche Signatur");
        assertTrue(sig1.matches("[0-9a-f]{64}"), "64 Hex-Zeichen (SHA-256)");
        assertNotEquals(sig1, sig3, "anderes Secret -> andere Signatur");
    }

    // ── dispatch() ───────────────────────────────────────────

    @Test
    void dispatch_erfolgreicheZustellung_setztStatusOk() {
        when(crypto.decrypt("enc(geheim)")).thenReturn("geheim");
        when(deliveryRepo.save(any())).thenAnswer(inv -> {
            WebhookDelivery d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(1L);
            }
            return d;
        });
        when(httpClient.post(anyString(), anyMap(), anyString()))
                .thenReturn(new WebhookHttpClient.DeliveryResult(true, 200, "ok"));

        WebhookEndpoint ep = endpoint(1L, "plaintext", "rechnung.created");
        WebhookDelivery delivery = service.dispatch(ep, "rechnung.created", "{}");

        assertEquals(WebhookDeliveryStatus.OK, delivery.getStatus());
        assertEquals(1, delivery.getAttempts());
        assertEquals(200, delivery.getHttpStatus());
        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    void dispatch_fehlgeschlageneZustellung_planeErstenRetryMitEinerMinute() {
        when(crypto.decrypt(any())).thenReturn("geheim");
        when(deliveryRepo.save(any())).thenAnswer(inv -> {
            WebhookDelivery d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(1L);
            }
            return d;
        });
        when(httpClient.post(anyString(), anyMap(), anyString()))
                .thenReturn(new WebhookHttpClient.DeliveryResult(false, 500, "server error"));

        WebhookEndpoint ep = endpoint(1L, "plaintext", "rechnung.created");
        WebhookDelivery delivery = service.dispatch(ep, "rechnung.created", "{}");

        assertEquals(WebhookDeliveryStatus.FAILED, delivery.getStatus());
        assertEquals(1, delivery.getAttempts());
        assertNotNull(delivery.getNextAttemptAt());
        long secondsUntilRetry = delivery.getNextAttemptAt().getEpochSecond() - Instant.now().getEpochSecond();
        assertTrue(secondsUntilRetry > 50 && secondsUntilRetry <= 60, "~1 Minute Backoff, war " + secondsUntilRetry + "s");
    }

    @Test
    void retry_nachFuenfFehlgeschlagenenVersuchen_gibtAuf() {
        when(crypto.decrypt(any())).thenReturn("geheim");
        when(deliveryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(httpClient.post(anyString(), anyMap(), anyString()))
                .thenReturn(new WebhookHttpClient.DeliveryResult(false, 500, "server error"));

        WebhookEndpoint ep = endpoint(1L, "plaintext", "rechnung.created");
        when(endpointRepo.findById(1L)).thenReturn(Optional.of(ep));

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setId(1L);
        delivery.setEndpointId(1L);
        delivery.setEventType("rechnung.created");
        delivery.setPayload("{}");

        for (int i = 0; i < 5; i++) {
            service.retry(delivery);
            assertEquals(WebhookDeliveryStatus.FAILED, delivery.getStatus(), "Versuch " + (i + 1));
        }
        service.retry(delivery); // 6. Versuch
        assertEquals(WebhookDeliveryStatus.GIVEN_UP, delivery.getStatus());
        assertNull(delivery.getNextAttemptAt());
        assertEquals(6, delivery.getAttempts());
    }

    @Test
    void retry_endpointNichtMehrVorhanden_gibtSofortAuf() {
        when(endpointRepo.findById(99L)).thenReturn(Optional.empty());
        when(deliveryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setId(5L);
        delivery.setEndpointId(99L);

        service.retry(delivery);

        assertEquals(WebhookDeliveryStatus.GIVEN_UP, delivery.getStatus());
        verify(httpClient, never()).post(any(), any(), any());
    }

    // ── onDomainEvent() ──────────────────────────────────────

    @Test
    void onDomainEvent_benachrichtigtNurAbonnierteEndpoints() {
        WebhookEndpoint abonniert = endpoint(1L, "plaintext", "rechnung.created,member.created");
        WebhookEndpoint nichtAbonniert = endpoint(2L, "plaintext", "member.created");
        when(endpointRepo.findByMandatAndEnabledTrueAndDeletedFalse("plaintext"))
                .thenReturn(List.of(abonniert, nichtAbonniert));
        when(crypto.decrypt(any())).thenReturn("geheim");
        when(deliveryRepo.save(any())).thenAnswer(inv -> {
            WebhookDelivery d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(1L);
            }
            return d;
        });
        when(httpClient.post(anyString(), anyMap(), anyString()))
                .thenReturn(new WebhookHttpClient.DeliveryResult(true, 200, "ok"));

        PlaintextDomainEvent event = new PlaintextDomainEvent(
                "rechnung.created", "Rechnung", "42", "plaintext", Map.of("betrag", "100"));
        service.onDomainEvent(event);

        verify(httpClient, org.mockito.Mockito.times(1)).post(eq("https://example.com/hook"), anyMap(), anyString());
    }

    @Test
    void onDomainEvent_andererMandant_wirdNichtBenachrichtigt() {
        when(endpointRepo.findByMandatAndEnabledTrueAndDeletedFalse("anderer-mandant"))
                .thenReturn(List.of());

        PlaintextDomainEvent event = new PlaintextDomainEvent(
                "rechnung.created", "Rechnung", "42", "anderer-mandant", Map.of());
        service.onDomainEvent(event);

        verify(httpClient, never()).post(any(), any(), any());
    }
}

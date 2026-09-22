/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.service;

import ch.plaintext.webhooks.entity.WebhookDelivery;
import ch.plaintext.webhooks.entity.WebhookEndpoint;
import ch.plaintext.webhooks.repository.WebhookDeliveryRepository;
import ch.plaintext.webhooks.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Die Aufrufstelle, an der die Webhook-Signierschlüssel verschlüsselt werden.
 *
 * <p>Der Test läuft bewusst mit dem <b>echten</b> {@link WebhookCrypto} (Dev-Fallback-Key, kein
 * Spring-Kontext) statt mit einem Mock: damit belegt er nicht nur, dass
 * {@code WebhookEndpointService} irgendetwas verschlüsselt, sondern dass die seit Karte 1301
 * geteilte Krypto an dieser Aufrufstelle unverändert verdrahtet ist — was hier gespeichert wird,
 * lässt sich mit derselben Klasse wieder lesen, und es ist nicht der Klartext.</p>
 *
 * <p>Vor Karte 1301 hatte dieser Service gar keinen Test; die Zusicherung „das Secret steht nie im
 * Klartext in der Spalte" stand nur im Javadoc der Entität.</p>
 */
@DisplayName("WebhookEndpointService: Signierschluessel nur verschluesselt")
class WebhookEndpointServiceTest {

    private final WebhookEndpointRepository endpointRepo = mock(WebhookEndpointRepository.class);
    private final WebhookDeliveryRepository deliveryRepo = mock(WebhookDeliveryRepository.class);
    private final WebhookDispatchService dispatchService = mock(WebhookDispatchService.class);
    private final WebhookCrypto crypto = new WebhookCrypto(new MockEnvironment());

    private final WebhookEndpointService service =
            new WebhookEndpointService(endpointRepo, deliveryRepo, crypto, dispatchService);

    private WebhookEndpoint gespeichert() {
        ArgumentCaptor<WebhookEndpoint> captor = ArgumentCaptor.forClass(WebhookEndpoint.class);
        verify(endpointRepo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("create gibt den Klartext genau einmal zurueck und legt ihn verschluesselt ab")
    void createLegtDasSecretVerschluesseltAb() {
        String klartext = service.create("demo", "Ziel", "https://example.org/hook", true, "rechnung.created");

        WebhookEndpoint gespeichert = gespeichert();
        assertEquals("demo", gespeichert.getMandat());
        assertEquals("Ziel", gespeichert.getName());
        assertEquals("https://example.org/hook", gespeichert.getUrl());
        assertEquals("rechnung.created", gespeichert.getEventTypes());
        assertTrue(gespeichert.isEnabled());

        assertNotEquals(klartext, gespeichert.getSigningSecretEncrypted(),
                "das Secret darf NIE im Klartext in der Spalte stehen");
        assertEquals(klartext, crypto.decrypt(gespeichert.getSigningSecretEncrypted()),
                "und es muss mit derselben Krypto wieder lesbar sein — sonst kann kein "
                        + "ausgehender Request mehr signiert werden");

        // base64url ohne Padding ueber 32 Byte
        assertEquals(43, klartext.length(), "32 Byte Zufall erwartet, war: " + klartext.length());
    }

    @Test
    @DisplayName("rotateSecret erzeugt ein anderes Secret und speichert es wieder verschluesselt")
    void rotateSecretErzeugtEinNeuesSecret() {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setName("Ziel");
        String alt = crypto.encrypt("altes-secret");
        endpoint.setSigningSecretEncrypted(alt);

        String neu = service.rotateSecret(endpoint);

        assertNotEquals("altes-secret", neu);
        assertNotEquals(alt, endpoint.getSigningSecretEncrypted());
        assertEquals(neu, crypto.decrypt(endpoint.getSigningSecretEncrypted()));
        assertSame(endpoint, gespeichert());
    }

    @Test
    @DisplayName("delete setzt nur die Loeschmarke")
    void deleteSetztNurDieMarke() {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setName("Ziel");

        service.delete(endpoint);

        assertTrue(gespeichert().getDeleted());
    }

    @Test
    @DisplayName("update reicht die Entitaet unveraendert durch")
    void updateReichtDurch() {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        String vorher = crypto.encrypt("unangetastet");
        endpoint.setSigningSecretEncrypted(vorher);

        service.update(endpoint);

        assertEquals(vorher, gespeichert().getSigningSecretEncrypted(),
                "update darf das Secret nicht anfassen — dafuer gibt es rotateSecret");
    }

    @Test
    @DisplayName("findAll und deliveryLog fragen mandanten- bzw. endpunktbezogen ab")
    void leseWegeFragenRichtigAb() {
        WebhookEndpoint e = new WebhookEndpoint();
        when(endpointRepo.findByMandatAndDeletedFalseOrderByNameAsc("demo")).thenReturn(List.of(e));
        WebhookDelivery d = new WebhookDelivery();
        when(deliveryRepo.findByEndpointIdOrderByCreatedDateDesc(7L)).thenReturn(List.of(d));

        assertEquals(1, service.findAll("demo").size());
        assertSame(e, service.findAll("demo").get(0));
        assertEquals(1, service.deliveryLog(7L).size());
        assertSame(d, service.deliveryLog(7L).get(0));
    }

    @Test
    @DisplayName("testPing schickt ein synthetisches webhook.test-Ereignis")
    void testPingSchicktWebhookTest() {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        WebhookDelivery erwartet = new WebhookDelivery();
        when(dispatchService.dispatch(any(), eq("webhook.test"), any())).thenReturn(erwartet);

        assertSame(erwartet, service.testPing(endpoint));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(dispatchService).dispatch(eq(endpoint), eq("webhook.test"), payload.capture());
        assertTrue(payload.getValue().contains("\"eventType\":\"webhook.test\""));
    }
}

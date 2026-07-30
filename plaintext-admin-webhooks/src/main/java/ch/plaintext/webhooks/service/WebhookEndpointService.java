/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.service;

import ch.plaintext.webhooks.entity.WebhookDelivery;
import ch.plaintext.webhooks.entity.WebhookEndpoint;
import ch.plaintext.webhooks.repository.WebhookDeliveryRepository;
import ch.plaintext.webhooks.repository.WebhookEndpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Verwaltung der {@link WebhookEndpoint}s (CRUD) + Delivery-Log-Zugriff + Test-Ping. Das
 * Signing-Secret wird bei {@link #create} zufällig erzeugt, verschlüsselt gespeichert und nur als
 * Rückgabewert dieser Methode einmalig im Klartext zurückgegeben (Muster wie {@code ApiToken}).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEndpointService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookEndpointRepository endpointRepo;
    private final WebhookDeliveryRepository deliveryRepo;
    private final WebhookCrypto crypto;
    private final WebhookDispatchService dispatchService;

    public List<WebhookEndpoint> findAll(String mandat) {
        return endpointRepo.findByMandatAndDeletedFalseOrderByNameAsc(mandat);
    }

    public List<WebhookDelivery> deliveryLog(Long endpointId) {
        return deliveryRepo.findByEndpointIdOrderByCreatedDateDesc(endpointId);
    }

    /** Legt einen neuen Endpoint an; generiert das Signing-Secret und gibt es einmalig im Klartext zurück. */
    @Transactional
    public String create(String mandat, String name, String url, boolean enabled, String eventTypes) {
        String secret = generateSecret();
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setMandat(mandat);
        endpoint.setName(name);
        endpoint.setUrl(url);
        endpoint.setEnabled(enabled);
        endpoint.setEventTypes(eventTypes);
        endpoint.setSigningSecretEncrypted(crypto.encrypt(secret));
        endpointRepo.save(endpoint);
        log.info("Webhook-Endpoint '{}' angelegt (Mandant {})", name, mandat);
        return secret;
    }

    /** Aktualisiert Stammdaten (NICHT das Secret — dafür {@link #rotateSecret}). */
    @Transactional
    public void update(WebhookEndpoint endpoint) {
        endpointRepo.save(endpoint);
    }

    /** Erzeugt ein neues Signing-Secret für einen bestehenden Endpoint; gibt es einmalig im Klartext zurück. */
    @Transactional
    public String rotateSecret(WebhookEndpoint endpoint) {
        String secret = generateSecret();
        endpoint.setSigningSecretEncrypted(crypto.encrypt(secret));
        endpointRepo.save(endpoint);
        log.info("Webhook-Endpoint '{}' (id={}): Secret rotiert", endpoint.getName(), endpoint.getId());
        return secret;
    }

    @Transactional
    public void delete(WebhookEndpoint endpoint) {
        endpoint.setDeleted(true);
        endpointRepo.save(endpoint);
        log.info("Webhook-Endpoint '{}' (id={}) geloescht", endpoint.getName(), endpoint.getId());
    }

    /** Verschickt ein synthetisches {@code webhook.test}-Event an den Endpoint (unabhängig von dessen eventTypes-Filter). */
    public WebhookDelivery testPing(WebhookEndpoint endpoint) {
        String payload = "{\"eventType\":\"webhook.test\",\"message\":\"Test-Ping von plaintext-admin-webhooks\"}";
        return dispatchService.dispatch(endpoint, "webhook.test", payload);
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

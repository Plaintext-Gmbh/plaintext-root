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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Receives {@link PlaintextDomainEvent}s (business modules publish them via
 * {@code ApplicationEventPublisher}, no cross-module call needed), determines the matching
 * active {@link WebhookEndpoint}s of the tenant and sends them best-effort synchronously
 * (errors never block the triggering business flow — analogous to {@code DestructiveActionAuditService}).
 * Failed deliveries are retried by {@link WebhookRetryCron} with exponential backoff.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatchService {

    /** Retry delays per attempt (1min/5min/30min/2h/12h) — after that {@code GIVEN_UP}. */
    static final Duration[] BACKOFF = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(12),
    };

    private final WebhookEndpointRepository endpointRepo;
    private final WebhookDeliveryRepository deliveryRepo;
    private final WebhookCrypto crypto;
    private final WebhookHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Sonar java:S2229 (card 891): dispatch() carries @Transactional, but was reached here via
    // SELF-INVOCATION — which bypasses the Spring proxy, so the annotation had no effect.
    // The transaction boundary therefore belongs here, and necessarily as REQUIRES_NEW: in the
    // AFTER_COMMIT phase the triggering transaction is still bound to the thread, but is already
    // completed — writes that join it would never be committed. Spring therefore only permits
    // REQUIRES_NEW or NOT_SUPPORTED here and rejects any other propagation already at context
    // startup ("@TransactionalEventListener method must not be annotated with @Transactional
    // unless when declared as REQUIRES_NEW or NOT_SUPPORTED").
    //
    // Status report 29.08.2026 (R2): The self-invocation itself is gone now. Both public entry
    // points — this listener and dispatch() for the test ping from WebhookEndpointService —
    // call the private core anlegenUndZustellen(); neither calls its own @Transactional method
    // any more. Deliberately NOT via an ObjectProvider<WebhookDispatchService> self-proxy (the
    // BuildstatsSyncService pattern in plaintext-app): with REQUIRED, dispatch() would only have
    // entered the REQUIRES_NEW bracket that is already open here, the detour via the proxy gained
    // nothing at runtime and would have changed the constructor (and hence both tests). The inner
    // @Transactional on dispatch() stays, because the external call WebhookEndpointService.testPing()
    // otherwise has no bracket: creating the delivery and writing the result are two repository
    // calls there that belong together. TransaktionsklammerVertragTest still measures exactly ONE
    // transaction per event.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDomainEvent(PlaintextDomainEvent event) {
        List<WebhookEndpoint> endpoints = endpointRepo.findByMandatAndEnabledTrueAndDeletedFalse(event.mandant());
        for (WebhookEndpoint endpoint : endpoints) {
            if (abonniert(endpoint, event.eventType())) {
                anlegenUndZustellen(endpoint, event.eventType(), payloadJson(event));
            }
        }
    }

    /**
     * Creates a new delivery and immediately attempts to deliver it best-effort. External entry
     * point (test ping from {@link WebhookEndpointService#testPing}); the transaction boundary here
     * exists for exactly this path — {@link #onDomainEvent} brings its own (REQUIRES_NEW) and calls
     * the core directly.
     */
    @Transactional
    public WebhookDelivery dispatch(WebhookEndpoint endpoint, String eventType, String payloadJson) {
        return anlegenUndZustellen(endpoint, eventType, payloadJson);
    }

    /** Shared core of {@link #onDomainEvent} and {@link #dispatch}: create the delivery, deliver it, write the result. */
    private WebhookDelivery anlegenUndZustellen(WebhookEndpoint endpoint, String eventType, String payloadJson) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setMandat(endpoint.getMandat());
        delivery.setEndpointId(endpoint.getId());
        delivery.setEventType(eventType);
        delivery.setPayload(payloadJson);
        delivery = deliveryRepo.save(delivery);
        versuchen(endpoint, delivery);
        return deliveryRepo.save(delivery);
    }

    /** From the retry cron: another delivery attempt for an already existing delivery. */
    @Transactional
    public void retry(WebhookDelivery delivery) {
        endpointRepo.findById(delivery.getEndpointId()).ifPresentOrElse(
                endpoint -> {
                    versuchen(endpoint, delivery);
                    deliveryRepo.save(delivery);
                },
                () -> {
                    delivery.setStatus(WebhookDeliveryStatus.GIVEN_UP);
                    delivery.setResponseSnippet("Endpoint nicht mehr vorhanden");
                    deliveryRepo.save(delivery);
                });
    }

    private void versuchen(WebhookEndpoint endpoint, WebhookDelivery delivery) {
        try {
            String secret = crypto.decrypt(endpoint.getSigningSecretEncrypted());
            String body = delivery.getPayload() != null ? delivery.getPayload() : "{}";
            Map<String, String> headers = headers(delivery, body, secret);
            WebhookHttpClient.DeliveryResult result = httpClient.post(endpoint.getUrl(), headers, body);

            delivery.setAttempts(delivery.getAttempts() + 1);
            delivery.setHttpStatus(result.httpStatus());
            delivery.setResponseSnippet(result.responseSnippet());
            if (result.success()) {
                delivery.setStatus(WebhookDeliveryStatus.OK);
                delivery.setNextAttemptAt(null);
            } else {
                planeRetryOderGibAuf(delivery);
            }
        } catch (Exception e) {
            log.warn("Webhook-Zustellversuch fehlgeschlagen (Endpoint {}, Delivery {}): {}",
                    endpoint.getId(), delivery.getId(), e.getMessage());
            delivery.setAttempts(delivery.getAttempts() + 1);
            delivery.setResponseSnippet(e.getMessage());
            planeRetryOderGibAuf(delivery);
        }
    }

    private void planeRetryOderGibAuf(WebhookDelivery delivery) {
        if (delivery.getAttempts() > BACKOFF.length) {
            delivery.setStatus(WebhookDeliveryStatus.GIVEN_UP);
            delivery.setNextAttemptAt(null);
        } else {
            delivery.setStatus(WebhookDeliveryStatus.FAILED);
            delivery.setNextAttemptAt(Instant.now().plus(BACKOFF[delivery.getAttempts() - 1]));
        }
    }

    private Map<String, String> headers(WebhookDelivery delivery, String body, String secret) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Plaintext-Event", delivery.getEventType());
        headers.put("X-Plaintext-Delivery", String.valueOf(delivery.getId()));
        headers.put("X-Plaintext-Timestamp", String.valueOf(Instant.now().getEpochSecond()));
        headers.put("X-Plaintext-Signature", "sha256=" + hmacSha256(body, secret));
        return headers;
    }

    static String hmacSha256(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-Signatur fehlgeschlagen", e);
        }
    }

    private static boolean abonniert(WebhookEndpoint endpoint, String eventType) {
        if (endpoint.getEventTypes() == null) {
            return false;
        }
        for (String type : endpoint.getEventTypes().split(",")) {
            if (type.trim().equals(eventType)) {
                return true;
            }
        }
        return false;
    }

    private String payloadJson(PlaintextDomainEvent event) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("eventType", event.eventType());
            body.put("entityType", event.entityType());
            body.put("entityId", event.entityId());
            if (event.payload() != null) {
                body.putAll(event.payload());
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("Webhook-Payload konnte nicht serialisiert werden: {}", e.getMessage());
            return "{}";
        }
    }
}

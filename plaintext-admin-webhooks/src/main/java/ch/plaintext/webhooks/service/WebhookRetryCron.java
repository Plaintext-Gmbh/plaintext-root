/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.service;

import ch.plaintext.PlaintextCron;
import ch.plaintext.webhooks.entity.WebhookDelivery;
import ch.plaintext.webhooks.entity.WebhookDeliveryStatus;
import ch.plaintext.webhooks.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Picks up the tenant's due {@link WebhookDeliveryStatus#FAILED} deliveries (backoff time reached)
 * and attempts to deliver them again — see {@link WebhookDispatchService#BACKOFF}.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
@Scope("prototype")
@RequiredArgsConstructor
@Slf4j
public class WebhookRetryCron implements PlaintextCron {

    private final WebhookDeliveryRepository deliveryRepo;
    private final WebhookDispatchService dispatchService;

    @Override
    public String getDisplayName() {
        return "Webhook-Zustellung: Retry fehlgeschlagener Deliveries";
    }

    @Override
    public String getDefaultCronExpression() {
        return "*/5 * * * *";
    }

    @Override
    public void run(String mandant) {
        List<WebhookDelivery> faellige = deliveryRepo.findByMandatAndStatusInAndNextAttemptAtBefore(
                mandant, List.of(WebhookDeliveryStatus.FAILED), Instant.now());
        if (faellige.isEmpty()) {
            return;
        }
        log.info("Webhook-Retry: {} faellige Delivery(s) fuer Mandant {}", faellige.size(), mandant);
        for (WebhookDelivery delivery : faellige) {
            dispatchService.retry(delivery);
        }
    }
}

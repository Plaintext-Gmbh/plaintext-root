/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.service;

import ch.plaintext.PlaintextCron;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Deletes read notifications older than {@link #RETENTION_DAYS} days. Cross-tenant
 * (a tenant-by-tenant repetition makes no sense), hence {@link #isGlobal()} = {@code true}. */
@Component
@Scope("prototype")
@RequiredArgsConstructor
@Slf4j
public class NotificationCleanupCron implements PlaintextCron {

    private static final int RETENTION_DAYS = 90;

    private final NotificationServiceImpl notificationService;

    @Override
    public boolean isGlobal() {
        return true;
    }

    @Override
    public String getDisplayName() {
        return "Benachrichtigungen: gelesene älter als " + RETENTION_DAYS + " Tage aufräumen";
    }

    @Override
    public String getDefaultCronExpression() {
        return "0 3 * * *";
    }

    @Override
    public void run(String mandant) {
        int n = notificationService.cleanupGelesenAelterAls(LocalDateTime.now().minusDays(RETENTION_DAYS));
        if (n > 0) {
            log.info("Notification-Cleanup: {} gelesene Benachrichtigung(en) älter als {} Tage gelöscht", n, RETENTION_DAYS);
        }
    }
}

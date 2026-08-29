/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Zustandsbericht 29.08.2026, Massnahme 13 (JaCoCo-Gate): der Aufraeum-Cron ist global und
 * loescht nur gelesene Benachrichtigungen aelter als 90 Tage — die Grenze ist der Vertrag.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationCleanupCron")
class NotificationCleanupCronTest {

    @Mock
    private NotificationServiceImpl service;

    @Test
    void istGlobalMitTaeglichemLaufUmDrei() {
        NotificationCleanupCron cron = new NotificationCleanupCron(service);

        assertTrue(cron.isGlobal());
        assertEquals("0 3 * * *", cron.getDefaultCronExpression());
        assertTrue(cron.getDisplayName().contains("90 Tage"));
    }

    @Test
    void runLoeschtGeleseneAelterAls90Tage() {
        when(service.cleanupGelesenAelterAls(any())).thenReturn(3);
        LocalDateTime vorher = LocalDateTime.now().minusDays(90);

        new NotificationCleanupCron(service).run("egal");

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(service).cleanupGelesenAelterAls(captor.capture());
        LocalDateTime grenze = captor.getValue();
        assertTrue(!grenze.isBefore(vorher) && !grenze.isAfter(LocalDateTime.now().minusDays(90)),
                "Grenze liegt genau 90 Tage zurueck: " + grenze);
    }

    @Test
    void runOhneTrefferBleibtStill() {
        when(service.cleanupGelesenAelterAls(any())).thenReturn(0);

        new NotificationCleanupCron(service).run(null);

        verify(service).cleanupGelesenAelterAls(any());
    }
}

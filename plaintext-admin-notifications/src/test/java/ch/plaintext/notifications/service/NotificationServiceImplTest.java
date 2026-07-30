/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.mailtemplate.IMailTemplateProvider;
import ch.plaintext.mailtemplate.IMailTemplateProvider.RenderedMail;
import ch.plaintext.notifications.entity.Notification;
import ch.plaintext.notifications.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private IMailTemplateProvider mailTemplateProvider;
    @Mock private PlaintextSecurity security;

    private NotificationServiceImpl service() {
        return new NotificationServiceImpl(notificationRepository, mailTemplateProvider, security);
    }

    @Test
    void notify_rendertUeberMailTemplateProviderUndSpeichert() {
        when(mailTemplateProvider.render(eq("m1"), eq("notif.test"), anyString(), anyString(), any()))
                .thenReturn(new RenderedMail("Gerenderter Titel", "Gerenderter Text"));

        service().notify("daniel", "m1", "test", "Default-Titel", "Default-Text", Map.of("x", "y"), "/foo.html");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification n = captor.getValue();
        assertThat(n.getEmpfaengerUsername()).isEqualTo("daniel");
        assertThat(n.getMandat()).isEqualTo("m1");
        assertThat(n.getTyp()).isEqualTo("test");
        assertThat(n.getTitel()).isEqualTo("Gerenderter Titel");
        assertThat(n.getText()).isEqualTo("Gerenderter Text");
        assertThat(n.getLink()).isEqualTo("/foo.html");
        assertThat(n.getGelesenAm()).isNull();
    }

    @Test
    void notify_leererEmpfaenger_speichertNichts() {
        service().notify("", "m1", "test", "T", "X", Map.of(), null);
        service().notify(null, "m1", "test", "T", "X", Map.of(), null);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void notifyMandant_erzeugtEineBenachrichtigungJeBenutzer() {
        when(security.getUsersForMandat("m1")).thenReturn(List.of("alice", "bob"));
        when(mailTemplateProvider.render(eq("m1"), eq("notif.broadcast"), anyString(), anyString(), any()))
                .thenReturn(new RenderedMail("T", "X"));

        service().notifyMandant("m1", "broadcast", "Default-Titel", "Default-Text", Map.of(), null);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<String> empfaenger = captor.getAllValues().stream().map(Notification::getEmpfaengerUsername).toList();
        assertThat(empfaenger).containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void markiereGelesen_setztZeitstempelNurBeiEigenerUngelesenerBenachrichtigung() {
        Notification n = new Notification();
        n.setId(5L);
        n.setEmpfaengerUsername("daniel");
        when(notificationRepository.findById(5L)).thenReturn(Optional.of(n));

        service().markiereGelesen(5L, "daniel");

        assertThat(n.getGelesenAm()).isNotNull();
        verify(notificationRepository).save(n);
    }

    @Test
    void markiereGelesen_fremderBenutzer_tutNichts() {
        Notification n = new Notification();
        n.setId(5L);
        n.setEmpfaengerUsername("daniel");
        when(notificationRepository.findById(5L)).thenReturn(Optional.of(n));

        service().markiereGelesen(5L, "fremder");

        assertThat(n.getGelesenAm()).isNull();
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void cleanupGelesenAelterAls_loeschtGefundeneUndGibtAnzahlZurueck() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        Notification a = new Notification();
        Notification b = new Notification();
        when(notificationRepository.findByGelesenAmIsNotNullAndGelesenAmBefore(cutoff)).thenReturn(List.of(a, b));

        int deleted = service().cleanupGelesenAelterAls(cutoff);

        assertThat(deleted).isEqualTo(2);
        verify(notificationRepository).deleteAll(List.of(a, b));
    }

    @Test
    void cleanupGelesenAelterAls_keineTreffer_loeschtNicht() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        when(notificationRepository.findByGelesenAmIsNotNullAndGelesenAmBefore(cutoff)).thenReturn(List.of());

        int deleted = service().cleanupGelesenAelterAls(cutoff);

        assertThat(deleted).isEqualTo(0);
        verify(notificationRepository, never()).deleteAll(any());
    }
}

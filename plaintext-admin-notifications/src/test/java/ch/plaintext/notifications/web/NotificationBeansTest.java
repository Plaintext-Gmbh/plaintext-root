/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.web;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.notifications.entity.Notification;
import ch.plaintext.notifications.service.NotificationServiceImpl;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Zustandsbericht 29.08.2026, Massnahme 13 (JaCoCo-Gate): die beiden JSF-Beans des Moduls —
 * Glocke im Header (request-scoped, ohne Login still) und Inbox-Seite (session-scoped).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationBeansTest {

    @Mock
    private NotificationServiceImpl service;

    private MockedStatic<PlaintextSecurityHolder> holder;

    @BeforeEach
    void setUp() {
        holder = mockStatic(PlaintextSecurityHolder.class);
        holder.when(PlaintextSecurityHolder::getUser).thenReturn("daniel");
        holder.when(PlaintextSecurityHolder::getMandat).thenReturn("plaintext");
    }

    @AfterEach
    void tearDown() {
        holder.close();
    }

    private static Notification notification(long id) {
        Notification n = new Notification();
        n.setId(id);
        n.setTitel("T" + id);
        return n;
    }

    @Nested
    @DisplayName("NotificationTopbarBean (Glocke)")
    class Topbar {

        private NotificationTopbarBean bean;

        @BeforeEach
        void bean() {
            bean = new NotificationTopbarBean();
            ReflectionTestUtils.setField(bean, "notificationService", service);
        }

        @Test
        void ohneAngemeldetenBenutzerBleibtDieGlockeLeer() {
            holder.when(PlaintextSecurityHolder::getUser).thenReturn(null);

            assertEquals(0, bean.getUngelesenCount());
            assertTrue(bean.getLetzte().isEmpty());
            verify(service, never()).countUngelesen(anyString());
            verify(service, never()).getInbox(anyString(), anyInt());
        }

        @Test
        void zaehltUndZeigtDieLetztenAchtDesBenutzers() {
            List<Notification> letzte = List.of(notification(1));
            when(service.countUngelesen("daniel")).thenReturn(4L);
            when(service.getInbox("daniel", 8)).thenReturn(letzte);

            assertEquals(4, bean.getUngelesenCount());
            assertSame(letzte, bean.getLetzte());
        }

        @Test
        void markierenDelegiertMitDemBenutzer() {
            bean.markiereGelesen(5L);
            bean.markiereAlleGelesen();

            verify(service).markiereGelesen(5L, "daniel");
            verify(service).markiereAlleGelesen("daniel");
        }
    }

    @Nested
    @DisplayName("NotificationBackingBean (Inbox-Seite)")
    class Inbox {

        @Mock
        private FacesContext facesContext;

        private MockedStatic<FacesContext> facesStatic;
        private NotificationBackingBean bean;

        @BeforeEach
        void bean() {
            facesStatic = mockStatic(FacesContext.class);
            facesStatic.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            bean = new NotificationBackingBean();
            ReflectionTestUtils.setField(bean, "notificationService", service);
        }

        @AfterEach
        void schliessen() {
            facesStatic.close();
        }

        private FacesMessage letzteMeldung() {
            ArgumentCaptor<FacesMessage> captor = ArgumentCaptor.forClass(FacesMessage.class);
            verify(facesContext).addMessage(isNull(), captor.capture());
            return captor.getValue();
        }

        @Test
        void onLoadHoltBisZu200Eintraege() {
            List<Notification> inbox = List.of(notification(1), notification(2));
            when(service.getInbox("daniel", 200)).thenReturn(inbox);

            bean.onLoad();

            assertSame(inbox, bean.getNotifications());
        }

        @Test
        void markiereGelesenAktualisiertDieListe() {
            bean.markiereGelesen(notification(9));

            verify(service).markiereGelesen(9L, "daniel");
            verify(service).getInbox("daniel", 200);
        }

        @Test
        void markiereAlleGelesenMeldetDieAnzahl() {
            when(service.markiereAlleGelesen("daniel")).thenReturn(3);

            bean.markiereAlleGelesen();

            FacesMessage m = letzteMeldung();
            assertEquals(FacesMessage.SEVERITY_INFO, m.getSeverity());
            assertTrue(m.getDetail().startsWith("3 "), m.getDetail());
            verify(service).getInbox("daniel", 200);
        }

        @Test
        void testBenachrichtigungGehtAnDenAngemeldetenBenutzer() {
            bean.sendeTestBenachrichtigung();

            verify(service).notify(eq("daniel"), eq("plaintext"), eq("test"), eq("Test-Benachrichtigung"),
                    anyString(), eq(Map.of()), isNull());
            verify(service, times(1)).getInbox("daniel", 200);
            assertEquals("Gesendet", letzteMeldung().getSummary());
            verify(facesContext).addMessage(isNull(), any());
        }
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.mailtemplate.IMailTemplateProvider;
import ch.plaintext.mailtemplate.IMailTemplateProvider.RenderedMail;
import ch.plaintext.notifications.NotificationService;
import ch.plaintext.notifications.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vertragstest zur Transaktionsklammer (Sonar java:S2229, Karte 891).
 *
 * <p><b>Warum dieser Test existiert.</b> {@code notifyMandant} erreichte {@code notify} per
 * Selbstaufruf. Ein Selbstaufruf geht nicht durch den Spring-Proxy, also blieb dessen
 * {@code @Transactional} wirkungslos — jede {@code save()} committete für sich, obwohl der Code das
 * Gegenteil versprach. Der Fehler ist an der Quelle unsichtbar: die Annotation steht da, sie tut
 * nur nichts. Genau deshalb genügt es nicht, sie an die äussere Methode zu schreiben und den
 * Befund abzuhaken — dieser Test <em>misst</em>, ob beim Aufruf von aussen tatsächlich eine
 * Transaktion angefordert wird, und würde erneut rot, wenn jemand die Annotation entfernt.</p>
 *
 * <p>Gemessen wird am {@link PlatformTransactionManager}: der Transaktionsinterceptor fordert dort
 * für jede Klammer eine Transaktion an. Ein Mock zählt diese Anforderungen mit — das braucht weder
 * Datenbank noch Schema und misst trotzdem das echte Proxy-Verhalten.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class TransaktionsklammerVertragTest {

    private final NotificationRepository repo = mock(NotificationRepository.class);
    private final IMailTemplateProvider templates = mock(IMailTemplateProvider.class);
    private final PlaintextSecurity security = mock(PlaintextSecurity.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    @Configuration
    @EnableTransactionManagement
    static class Konfig {
        @Bean
        NotificationServiceImpl notificationService(NotificationRepository repo,
                                                    IMailTemplateProvider templates,
                                                    PlaintextSecurity security) {
            return new NotificationServiceImpl(repo, templates, security);
        }
    }

    private AnnotationConfigApplicationContext kontext() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(NotificationRepository.class, () -> repo);
        ctx.registerBean(IMailTemplateProvider.class, () -> templates);
        ctx.registerBean(PlaintextSecurity.class, () -> security);
        ctx.registerBean(PlatformTransactionManager.class, () -> txManager);
        ctx.register(Konfig.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    void einzelnesNotifyOeffnetEineTransaktion() {
        // Positivkontrolle: ohne sie belegt die Zählung im Test darunter nichts — eine Messung, die
        // gar keine Transaktion sehen kann, meldet genauso 0 wie eine fehlende Annotation.
        when(templates.render(any(), any(), any(), any(), any())).thenReturn(new RenderedMail("T", "B"));

        try (AnnotationConfigApplicationContext ctx = kontext()) {
            ctx.getBean(NotificationService.class)
                    .notify("anna", "m1", "typ", "Titel", "Text", Map.of(), "/ziel");
        }

        verify(txManager, times(1)).getTransaction(any());
        verify(repo, times(1)).save(any());
    }

    @Test
    void notifyMandantKlammertAlleEmpfaengerInEineTransaktion() {
        when(security.getUsersForMandat("m1")).thenReturn(List.of("anna", "bea", "chris"));
        when(templates.render(any(), any(), any(), any(), any())).thenReturn(new RenderedMail("T", "B"));

        try (AnnotationConfigApplicationContext ctx = kontext()) {
            ctx.getBean(NotificationService.class)
                    .notifyMandant("m1", "typ", "Titel", "Text", Map.of(), "/ziel");
        }

        // Drei Benachrichtigungen, aber nur EINE Transaktion: die äussere Klammer deckt alle ab.
        // Die 1 belegt zugleich beide Seiten des Befundes — vor der Behebung wäre sie 0 gewesen
        // (keine Klammer), und dass sie nicht 3 ist, zeigt, dass der Selbstaufruf nach wie vor am
        // Proxy vorbeigeht. Er darf es jetzt, weil die Klammer schon offen ist.
        verify(repo, times(3)).save(any());
        verify(txManager, times(1)).getTransaction(any());
    }
}

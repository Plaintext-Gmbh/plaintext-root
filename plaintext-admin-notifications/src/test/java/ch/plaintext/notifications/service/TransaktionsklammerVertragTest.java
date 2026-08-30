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
 * Contract test for the transaction boundary (Sonar java:S2229, card 891).
 *
 * <p><b>Why this test exists.</b> {@code notifyMandant} reached {@code notify} by
 * self-invocation. A self-invocation does not go through the Spring proxy, so its
 * {@code @Transactional} had no effect — every {@code save()} committed on its own, although the
 * code promised the opposite. The bug is invisible in the source: the annotation is there, it
 * simply does nothing. That is exactly why it is not enough to move it to the outer method and
 * tick the finding off — this test <em>measures</em> whether a transaction is actually requested
 * on a call from outside, and would go red again if someone removed the annotation.</p>
 *
 * <p>The measurement is taken at the {@link PlatformTransactionManager}: the transaction
 * interceptor requests a transaction there for every boundary. A mock counts those requests — that
 * needs neither a database nor a schema and still measures the real proxy behaviour.</p>
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
        // Positive control: without it the count in the test below proves nothing — a measurement
        // that cannot see a transaction at all reports 0 just like a missing annotation.
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

        // Three notifications, but only ONE transaction: the outer boundary covers them all.
        // The 1 proves both sides of the finding at once — before the fix it would have been 0
        // (no boundary), and the fact that it is not 3 shows that the self-invocation still
        // bypasses the proxy. It is allowed to now, because the boundary is already open.
        verify(repo, times(3)).save(any());
        verify(txManager, times(1)).getTransaction(any());
    }
}

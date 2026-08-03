/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.PlaintextCron;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the replaceable configuration store, the application-wide defaults, and the key a job is
 * filed under.
 */
class CronConfigStoreTest {

    @Nested
    class Vorgaben {

        @Test
        void greifenAusDenProperties() {
            CronProperties p = new CronProperties();
            assertThat(p.isDefaultEnabled()).isTrue();
            assertThat(p.isDefaultStartup()).isTrue();
            assertThat(p.getStore()).isEqualTo("jpa");
        }

        @Test
        void werdenVomJobUeberstimmt() {
            PlaintextCron job = new PlaintextCron() {
                @Override
                public void run(String mandant) {
                    // no-op
                }

                @Override
                public Boolean isStartupByDefault() {
                    return false;
                }
            };
            assertThat(job.isStartupByDefault()).isFalse();
            assertThat(job.isEnabledByDefault())
                    .as("ohne eigene Meinung faellt der Job auf die Anwendungsvorgabe zurueck")
                    .isNull();
        }
    }

    @Nested
    class Schluessel {

        /**
         * A bean carrying {@code @Transactional} or {@code @Async} is wrapped into a CGLIB proxy.
         * Without {@code ClassUtils.getUserClass} the generated name would end up as the key in
         * the cron configuration and as the label in the UI — and the job would never find its
         * stored configuration again.
         */
        @Test
        void loestEinenCglibProxyAufDenKlarnamenAuf() {
            PlaintextCron echt = new PlaintextCron() {
                @Override
                public void run(String mandant) {
                    // no-op
                }
            };

            ProxyFactory factory = new ProxyFactory(echt);
            factory.setProxyTargetClass(true);
            PlaintextCron proxy = (PlaintextCron) factory.getProxy();

            assertThat(proxy.getClass().getSimpleName())
                    .as("Vorbedingung: der Proxy traegt einen generierten Namen")
                    .contains("$$");

            ApplicationContext ctx = mock(ApplicationContext.class);
            when(ctx.isSingleton(anyString())).thenReturn(false);

            CronBeanPostProcessor processor = new CronBeanPostProcessor();
            processor.setApplicationContext(ctx);

            Object ergebnis = processor.postProcessAfterInitialization(proxy, "meinCron");

            assertThat(ergebnis).isInstanceOf(SuperCron.class);
            assertThat(((SuperCron) ergebnis).getName())
                    .doesNotContain("$$")
                    .isEqualTo(echt.getClass().getSimpleName());
        }
    }

    @Nested
    class JpaStore {

        @Test
        void reichtAnDasRepositoryDurch() {
            CronConfigRepository repo = mock(CronConfigRepository.class);
            CronConfigEntity entity = new CronConfigEntity();
            when(repo.findAll()).thenReturn(List.of(entity));
            when(repo.findByCronNameAndMandat("A", "global")).thenReturn(Optional.of(entity));
            when(repo.save(entity)).thenReturn(entity);

            CronConfigStore store = new JpaCronConfigStore(repo);

            assertThat(store.findAll()).containsExactly(entity);
            assertThat(store.findByCronNameAndMandat("A", "global")).contains(entity);
            assertThat(store.save(entity)).isSameAs(entity);
        }
    }
}

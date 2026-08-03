/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;

/**
 * Wires the cron module's replaceable parts, and makes the module usable outside plaintext-root.
 * <p>
 * The classes in this module carry {@code @Component} / {@code @Named} and are found as long as
 * the application component-scans {@code ch.plaintext}. Consumers outside plaintext-root do not —
 * they scan their own package. Registering the beans here explicitly makes the module work there
 * too.
 * <p>
 * {@code @ConditionalOnMissingBean} keeps an application that <em>does</em> scan
 * {@code ch.plaintext} from getting a second instance: the scanned bean wins and these methods do
 * not run.
 *
 * @since 1.480.0
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(CronProperties.class)
public class CronModuleConfiguration {

    /**
     * The JPA-backed default store, active unless {@code plaintext.cron.store} says otherwise.
     * <p>
     * The switch is a property rather than {@code @ConditionalOnMissingBean} or
     * {@code @ConditionalOnBean(CronConfigRepository.class)} on purpose. Bean-presence conditions
     * depend on registration order, and Spring Data repositories are contributed by a registry
     * post-processor — so the condition can evaluate before the repository exists and silently
     * skip this bean, leaving the application without a store. A property is evaluated the same
     * way every time.
     * <p>
     * An application that keeps its cron configuration elsewhere sets
     * {@code plaintext.cron.store: custom} and contributes its own {@link CronConfigStore}.
     *
     * @param repository the cron configuration repository
     * @return the JPA-backed store
     */
    @Bean
    @ConditionalOnProperty(prefix = "plaintext.cron", name = "store",
            havingValue = "jpa", matchIfMissing = true)
    public CronConfigStore jpaCronConfigStore(CronConfigRepository repository) {
        log.info("Using JpaCronConfigStore for cron configuration");
        return new JpaCronConfigStore(repository);
    }

    /**
     * Wraps every {@link ch.plaintext.PlaintextCron} implementation into a {@link SuperCron}. Must
     * be {@code static}: a BeanPostProcessor is created very early, and a non-static factory
     * method would drag the enclosing configuration up with it.
     *
     * @return the post processor
     */
    @Bean
    @ConditionalOnMissingBean
    public static CronBeanPostProcessor cronBeanPostProcessor() {
        return new CronBeanPostProcessor();
    }

    /**
     * Registers and schedules the cron jobs.
     *
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean
    public CronController cronController() {
        return new CronController();
    }

    /**
     * Backing bean of the cron overview ({@code cron.xhtml}).
     * <p>
     * Session scope as annotated on the class — and necessarily so: its {@code @PostConstruct}
     * asks {@link ch.plaintext.PlaintextSecurity} for the logged-in user's role. As a singleton
     * that would run at application startup, where there is no security context yet.
     *
     * @return the backing bean
     */
    @Bean
    @ConditionalOnMissingBean
    @Scope(value = "session", proxyMode = ScopedProxyMode.TARGET_CLASS)
    public CronBackingBean cronBackingBean() {
        return new CronBackingBean();
    }
}

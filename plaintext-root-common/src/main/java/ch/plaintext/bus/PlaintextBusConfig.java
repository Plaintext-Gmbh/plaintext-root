/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated, small thread pool for the bus delivery ({@link PlaintextBusDispatcher}) —
 * separate from the default {@code SimpleAsyncTaskExecutor} that {@code @Async} otherwise uses
 * throughout the repository for lack of a named executor bean (unbounded, one thread per call).
 *
 * <p>If the consuming app has not enabled {@code @EnableAsync}, {@code @Async} simply does not
 * take effect on {@link PlaintextBusDispatcher} (Spring ignores the annotation then) — the
 * delivery then continues synchronously on the commit thread, so it stays correct, only without
 * the concurrency benefit.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Configuration
public class PlaintextBusConfig {

    public static final String EXECUTOR_BEAN_NAME = "plaintextBusExecutor";

    @Bean(EXECUTOR_BEAN_NAME)
    public Executor plaintextBusExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("plaintext-bus-");
        executor.initialize();
        return executor;
    }
}

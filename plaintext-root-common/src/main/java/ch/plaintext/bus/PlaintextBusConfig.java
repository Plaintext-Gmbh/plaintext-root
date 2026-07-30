/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.bus;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedizierter, kleiner Thread-Pool für die Bus-Zustellung ({@link PlaintextBusDispatcher}) —
 * getrennt vom Default-{@code SimpleAsyncTaskExecutor}, den {@code @Async} sonst im gesamten Repo
 * mangels eines benannten Executor-Beans nutzt (unbounded, Thread-pro-Aufruf).
 *
 * <p>Falls die konsumierende App kein {@code @EnableAsync} aktiviert hat, greift {@code @Async} auf
 * {@link PlaintextBusDispatcher} einfach nicht (Spring ignoriert die Annotation dann) — die
 * Zustellung läuft in diesem Fall synchron auf dem Commit-Thread weiter, bleibt also korrekt, nur
 * ohne den Nebenläufigkeits-Vorteil.</p>
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

/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Wires the watch module into any application that puts it on the classpath.
 *
 * <p>Registered through {@code META-INF/spring/…AutoConfiguration.imports}, the same way the
 * other root modules do it — an application only adds the dependency, nothing else.</p>
 */
@AutoConfiguration
@ComponentScan("ch.plaintext.watch")
@EntityScan("ch.plaintext.watch")
@EnableJpaRepositories("ch.plaintext.watch")
public class WatchModuleConfiguration {
}

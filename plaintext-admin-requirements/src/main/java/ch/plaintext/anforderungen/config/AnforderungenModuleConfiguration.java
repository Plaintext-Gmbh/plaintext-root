/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registers the module's beans, entities and repositories.
 *
 * <p>Since Karte 1520 there is no {@code AutoConfiguration.imports} entry any more: this module
 * is not part of {@code plaintext-root-webapp} (whoever wants it declares it, as plaintext-app
 * does) and therefore reaches the shell through the module loader, which rejects module jars
 * that hook into the Spring start. The class is found by the {@code ch.plaintext} component
 * scan of the shell and of plaintext-app. An application that scans its own package only
 * enables it with {@code @ImportAutoConfiguration(AnforderungenModuleConfiguration.class)}.
 * The consumer still finds the menu classes via {@code plaintext.menu.scan-package}
 * (comma-separated list).
 *
 * @since 1.508.0
 */
@AutoConfiguration
@ComponentScan("ch.plaintext.anforderungen")
@EntityScan("ch.plaintext.anforderungen")
@EnableJpaRepositories("ch.plaintext.anforderungen")
public class AnforderungenModuleConfiguration {
}

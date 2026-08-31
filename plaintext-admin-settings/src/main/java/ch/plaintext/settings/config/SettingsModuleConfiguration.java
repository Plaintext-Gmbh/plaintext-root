/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Makes the settings module usable outside of plaintext-root - the same
 * pattern as {@code CronModuleConfiguration}.
 * <p>
 * The classes of this module carry {@code @Component}/{@code @Service} and
 * are found as long as the application component-scans {@code ch.plaintext}.
 * Consumers outside of plaintext-root do not do that - they scan their
 * own package. This AutoConfiguration registers the module's beans, entities
 * ({@code Setting}, {@code SetupConfig}, {@code BrandingLogo}) and repositories
 * explicitly.
 * <p>
 * Nothing ends up duplicated in the root webapp:
 * {@code @SpringBootApplication} excludes AutoConfiguration classes from the
 * component scan ({@code AutoConfigurationExcludeFilter}), and it is loaded
 * exactly once via the imports file; the repository registration overlaps
 * with the webapp's own ({@code @EnableJpaRepositories("ch.plaintext")}), which
 * stays without consequence thanks to its {@code allow-bean-definition-overriding}.
 * <p>
 * What consumers still have to do themselves: the module's menu classes
 * ({@code SettingsSubmenu}, {@code SetupSubmenu}) are found via
 * {@code plaintext.menu.scan-package} - add
 * {@code ch.plaintext.settings} there (comma-separated list).
 *
 * @since 1.505.0
 */
@AutoConfiguration
@ComponentScan("ch.plaintext.settings")
@EntityScan("ch.plaintext.settings")
@EnableJpaRepositories("ch.plaintext.settings")
public class SettingsModuleConfiguration {
}

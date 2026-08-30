/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.oidc.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Makes the module usable outside of plaintext-root - the same pattern as
 * {@code CronModuleConfiguration} and {@code SettingsModuleConfiguration}:
 * consumers scan their own package, not {@code ch.plaintext}; this
 * AutoConfiguration registers the module's beans, entities and repositories
 * explicitly. Loaded via the imports file; excluded from the webapp's component
 * scan as an AutoConfiguration. The consumer still finds the menu classes via
 * {@code plaintext.menu.scan-package} (comma-separated list).
 *
 * @since 1.508.0
 */
@AutoConfiguration
@ComponentScan("ch.plaintext.oidc")
@EntityScan("ch.plaintext.oidc")
@EnableJpaRepositories("ch.plaintext.oidc")
public class OidcModuleConfiguration {
}

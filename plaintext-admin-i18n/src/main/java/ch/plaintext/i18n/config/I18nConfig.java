/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Makes the i18n module usable outside plaintext-root - the same pattern
 * as {@code CronModuleConfiguration} and {@code SettingsModuleConfiguration}:
 * consumers scan their own package, not {@code ch.plaintext}; this
 * auto-configuration explicitly registers the module's beans (among them the EL bean
 * {@code #{i18n}}), entity ({@code I18nTranslation}) and repository.
 * It is loaded through the imports file; as an auto-configuration it is
 * excluded from the web application's component scan.
 *
 * @since 1.505.0 (previously a plain @Configuration with no registration path)
 */
@AutoConfiguration
@ComponentScan("ch.plaintext.i18n")
@EntityScan("ch.plaintext.i18n")
@EnableJpaRepositories("ch.plaintext.i18n")
public class I18nConfig {
}

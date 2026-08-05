/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Macht das i18n-Modul ausserhalb von plaintext-root nutzbar - dasselbe Muster
 * wie {@code CronModuleConfiguration} bzw. {@code SettingsModuleConfiguration}:
 * Konsumenten scannen ihr eigenes Package, nicht {@code ch.plaintext}; diese
 * AutoConfiguration registriert Beans (u.a. die EL-Bean {@code #{i18n}}),
 * Entity ({@code I18nTranslation}) und Repository des Moduls explizit.
 * Geladen wird sie ueber die imports-Datei; vom Component-Scan der Webapp ist
 * sie als AutoConfiguration ausgeschlossen.
 *
 * @since 1.505.0 (vorher reine @Configuration ohne Registrierungsweg)
 */
@AutoConfiguration
@ComponentScan("ch.plaintext.i18n")
@EntityScan("ch.plaintext.i18n")
@EnableJpaRepositories("ch.plaintext.i18n")
public class I18nConfig {
}

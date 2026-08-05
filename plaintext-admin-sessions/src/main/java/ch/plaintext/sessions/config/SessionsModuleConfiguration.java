/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Macht das Modul ausserhalb von plaintext-root nutzbar - dasselbe Muster wie
 * {@code CronModuleConfiguration} bzw. {@code SettingsModuleConfiguration}:
 * Konsumenten scannen ihr eigenes Package, nicht {@code ch.plaintext}; diese
 * AutoConfiguration registriert Beans, Entities und Repositories des Moduls
 * explizit. Geladen ueber die imports-Datei; vom Component-Scan der Webapp als
 * AutoConfiguration ausgeschlossen. Menueklassen findet der Konsument weiterhin
 * ueber {@code plaintext.menu.scan-package} (Kommaliste).
 *
 * @since 1.508.0
 */
@AutoConfiguration
@ComponentScan("ch.plaintext.sessions")
@EntityScan("ch.plaintext.sessions")
@EnableJpaRepositories("ch.plaintext.sessions")
public class SessionsModuleConfiguration {
}

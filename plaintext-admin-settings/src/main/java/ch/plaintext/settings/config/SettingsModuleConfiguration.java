/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Macht das Settings-Modul ausserhalb von plaintext-root nutzbar - dasselbe
 * Muster wie {@code CronModuleConfiguration}.
 * <p>
 * Die Klassen dieses Moduls tragen {@code @Component}/{@code @Service} und
 * werden gefunden, solange die Anwendung {@code ch.plaintext} component-scannt.
 * Konsumenten ausserhalb von plaintext-root tun das nicht - sie scannen ihr
 * eigenes Package. Diese AutoConfiguration registriert Beans, Entities
 * ({@code Setting}, {@code SetupConfig}, {@code BrandingLogo}) und Repositories
 * des Moduls explizit.
 * <p>
 * In der Root-Webapp entsteht dadurch nichts doppelt:
 * {@code @SpringBootApplication} schliesst AutoConfiguration-Klassen vom
 * Component-Scan aus ({@code AutoConfigurationExcludeFilter}), geladen wird sie
 * genau einmal ueber die imports-Datei; die Repository-Registrierung ueberlappt
 * mit der der Webapp ({@code @EnableJpaRepositories("ch.plaintext")}), was mit
 * deren {@code allow-bean-definition-overriding} folgenlos bleibt.
 * <p>
 * Was Konsumenten weiterhin selbst tun muessen: die Menueklassen des Moduls
 * ({@code SettingsSubmenu}, {@code SetupSubmenu}) werden ueber
 * {@code plaintext.menu.scan-package} gefunden - dort
 * {@code ch.plaintext.settings} ergaenzen (Kommaliste).
 *
 * @since 1.505.0
 */
@AutoConfiguration
@ComponentScan("ch.plaintext.settings")
@EntityScan("ch.plaintext.settings")
@EnableJpaRepositories("ch.plaintext.settings")
public class SettingsModuleConfiguration {
}

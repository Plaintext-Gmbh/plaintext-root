/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe.link.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Bindet die Freigabe-Links (Karte 1476) in jede Anwendung ein, die das Modul auf dem Klassenpfad hat. */
@AutoConfiguration
@ComponentScan("ch.plaintext.freigabe.link")
@EntityScan("ch.plaintext.freigabe.link")
@EnableJpaRepositories("ch.plaintext.freigabe.link")
public class FreigabeLinkModuleConfiguration {
}

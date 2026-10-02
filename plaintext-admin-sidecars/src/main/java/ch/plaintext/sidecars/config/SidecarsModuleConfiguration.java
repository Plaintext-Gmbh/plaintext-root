/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Karte 1400: Sidecar-Registry. */
@AutoConfiguration
@ComponentScan("ch.plaintext.sidecars")
@EntityScan("ch.plaintext.sidecars")
@EnableJpaRepositories("ch.plaintext.sidecars")
public class SidecarsModuleConfiguration {
}

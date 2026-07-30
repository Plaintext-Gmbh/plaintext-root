/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;

/**
 * JPA configuration for the menuesteuerung module.
 * Ensures that entities are properly scanned.
 * Repository scanning is handled by Spring Boot's auto-configuration.
 *
 * @author plaintext.ch
 * @since 1.42.0
 */
@Configuration
@EntityScan(basePackages = "ch.plaintext.menuesteuerung.model")
public class MenuesteuerungJpaConfig {
}

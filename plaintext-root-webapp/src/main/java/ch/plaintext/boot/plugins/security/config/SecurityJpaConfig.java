/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;

/**
 * JPA configuration for all entities.
 * Scans all packages for JPA entities including ch.plaintext and ch packages.
 */
@Configuration
@EntityScan(basePackages = {"ch.plaintext", "ch"})
public class SecurityJpaConfig {
}

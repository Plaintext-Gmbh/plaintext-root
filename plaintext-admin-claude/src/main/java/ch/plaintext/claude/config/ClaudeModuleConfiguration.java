/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Registers the Claude module for applications that do not scan {@code ch.plaintext} (same pattern
 * as {@code WebhooksModuleConfiguration}). No entities: the consumption table is written with JDBC.
 */
@AutoConfiguration
@ComponentScan("ch.plaintext.claude")
public class ClaudeModuleConfiguration {
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

// @EnableMethodSecurity activates the evaluation of @PreAuthorize/@PostAuthorize. Without it those
// annotations are SILENTLY ignored — the barrier stands in the code and lets everybody through. The
// existing code made this decision twice in writing while the switch was missing
// (PlaintextSecurityImpl:501 "would therefore be annotated silently without effect", I18nExportController:49
// "the annotation would be a silent dummy") and therefore checked in the method body. Card 546.
@SpringBootApplication
@ComponentScan(basePackages = {"ch.plaintext"})
@EntityScan(basePackages = {"ch.plaintext"})
@EnableJpaRepositories(basePackages = {"ch.plaintext"})
@EnableScheduling
@EnableAsync
@EnableMethodSecurity
public class RootBootApplication {
    public static void main(String[] args) {
        SpringApplication.run(RootBootApplication.class, args);
    }
}

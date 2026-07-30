/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jpa;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/**
 * JPA Auditing Configuration
 * Enables automatic population of @CreatedDate, @LastModifiedDate, @CreatedBy, @LastModifiedBy
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaAuditingConfig {

    /**
     * Provides the current auditor (username) for @CreatedBy and @LastModifiedBy
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            // Get current user from PlaintextSecurityHolder
            String username = PlaintextSecurityHolder.getUser();

            // Return username or "system" if no user is logged in
            return Optional.ofNullable(username != null && !username.isEmpty() ? username : "system");
        };
    }
}

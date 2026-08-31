/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Provides the {@link SecurityContextRepository} as a bean of its own.
 *
 * <p>Deliberately pulled out of {@link PlaintextSecurityConfig}: the
 * {@link PlaintextAuthenticationSuccessHandler} needs the repository (to persist
 * an empty context in the TOTP gate), and {@code PlaintextSecurityConfig} in turn needs
 * the success handler. If the repository bean still lay in {@code PlaintextSecurityConfig},
 * a constructor cycle dependency would arise. This small, stateless config breaks
 * the cycle.
 */
@Configuration
public class SecurityContextRepositoryConfig {

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the menu module
 */
@Configuration
@Slf4j
public class MenuAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SecurityProvider defaultSecurityProvider() {
        log.info("Using default SecurityProvider (no security)");
        return new SecurityProvider() {
            @Override
            public boolean hasRole(String role) {
                return true;
            }

            @Override
            public boolean isSecurityEnabled() {
                return false;
            }
        };
    }

    @Bean
    public static MenuRegistryPostProcessor menuRegistryPostProcessor() {
        log.info("Registering MenuRegistryPostProcessor");
        return new MenuRegistryPostProcessor();
    }

    @Bean
    public MenuModelBuilder menuModelBuilder() {
        log.info("Registering MenuModelBuilder");
        return new MenuModelBuilder();
    }
}

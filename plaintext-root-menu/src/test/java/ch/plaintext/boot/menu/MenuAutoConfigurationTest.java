/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class MenuAutoConfigurationTest {

    private final MenuAutoConfiguration config = new MenuAutoConfiguration();

    @Test
    void shouldBeAnnotatedWithConfiguration() {
        assertTrue(MenuAutoConfiguration.class.isAnnotationPresent(Configuration.class));
    }

    @Test
    void defaultSecurityProvider_shouldReturnTrueForAnyRole() {
        SecurityProvider provider = config.defaultSecurityProvider();

        assertTrue(provider.hasRole("ADMIN"));
        assertTrue(provider.hasRole("USER"));
        assertTrue(provider.hasRole("ANY_ROLE"));
    }

    @Test
    void defaultSecurityProvider_shouldHaveSecurityDisabled() {
        SecurityProvider provider = config.defaultSecurityProvider();

        assertFalse(provider.isSecurityEnabled());
    }

    @Test
    void menuRegistryPostProcessor_shouldReturnNonNull() {
        MenuRegistryPostProcessor postProcessor = MenuAutoConfiguration.menuRegistryPostProcessor();

        assertNotNull(postProcessor);
    }

    @Test
    void menuModelBuilder_shouldReturnNonNull() {
        MenuModelBuilder builder = config.menuModelBuilder();

        assertNotNull(builder);
    }

    @Test
    void defaultSecurityProvider_shouldReturnTrueForNullRole() {
        SecurityProvider provider = config.defaultSecurityProvider();

        assertTrue(provider.hasRole(null));
    }

    @Test
    void defaultSecurityProvider_shouldReturnTrueForEmptyRole() {
        SecurityProvider provider = config.defaultSecurityProvider();

        assertTrue(provider.hasRole(""));
    }

    @Test
    void menuRegistryPostProcessor_shouldReturnNewInstanceEachCall() {
        MenuRegistryPostProcessor p1 = MenuAutoConfiguration.menuRegistryPostProcessor();
        MenuRegistryPostProcessor p2 = MenuAutoConfiguration.menuRegistryPostProcessor();

        assertNotSame(p1, p2);
    }
}

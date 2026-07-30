/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MenuesteuerungModuleConfiguration.
 *
 * @author plaintext.ch
 * @since 1.42.0
 */
@DisplayName("MenuesteuerungModuleConfiguration Tests")
class MenuesteuerungModuleConfigurationTest {

    @Test
    @DisplayName("Should be annotated with @Configuration")
    void shouldBeAnnotatedWithConfiguration() {
        assertTrue(MenuesteuerungModuleConfiguration.class.isAnnotationPresent(Configuration.class),
                "Class should be annotated with @Configuration");
    }

    @Test
    @DisplayName("Should be annotated with @ComponentScan")
    void shouldBeAnnotatedWithComponentScan() {
        assertTrue(MenuesteuerungModuleConfiguration.class.isAnnotationPresent(ComponentScan.class),
                "Class should be annotated with @ComponentScan");
    }

    @Test
    @DisplayName("Should scan menuesteuerung and boot.menu packages")
    void shouldScanCorrectPackages() {
        ComponentScan componentScan = MenuesteuerungModuleConfiguration.class.getAnnotation(ComponentScan.class);
        assertNotNull(componentScan);

        Set<String> scannedPackages = Set.of(componentScan.basePackages());
        assertTrue(scannedPackages.contains("ch.plaintext.menuesteuerung"),
                "Should scan ch.plaintext.menuesteuerung package");
        assertTrue(scannedPackages.contains("ch.plaintext.boot.menu"),
                "Should scan ch.plaintext.boot.menu package");
        assertEquals(2, scannedPackages.size(),
                "Should scan exactly two packages");
    }

    @Test
    @DisplayName("Should be instantiable")
    void shouldBeInstantiable() {
        assertDoesNotThrow(MenuesteuerungModuleConfiguration::new);
    }
}

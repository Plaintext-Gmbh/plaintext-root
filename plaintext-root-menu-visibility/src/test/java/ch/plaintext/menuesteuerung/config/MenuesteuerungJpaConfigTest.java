/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MenuesteuerungJpaConfig.
 *
 * @author plaintext.ch
 * @since 1.42.0
 */
@DisplayName("MenuesteuerungJpaConfig Tests")
class MenuesteuerungJpaConfigTest {

    @Test
    @DisplayName("Should be annotated with @Configuration")
    void shouldBeAnnotatedWithConfiguration() {
        assertTrue(MenuesteuerungJpaConfig.class.isAnnotationPresent(Configuration.class),
                "Class should be annotated with @Configuration");
    }

    @Test
    @DisplayName("Should be annotated with @EntityScan")
    void shouldBeAnnotatedWithEntityScan() {
        assertTrue(MenuesteuerungJpaConfig.class.isAnnotationPresent(EntityScan.class),
                "Class should be annotated with @EntityScan");
    }

    @Test
    @DisplayName("Should scan the correct entity package")
    void shouldScanCorrectEntityPackage() {
        EntityScan entityScan = MenuesteuerungJpaConfig.class.getAnnotation(EntityScan.class);
        assertNotNull(entityScan);
        assertArrayEquals(
                new String[]{"ch.plaintext.menuesteuerung.model"},
                entityScan.basePackages(),
                "EntityScan should target the menuesteuerung.model package"
        );
    }

    @Test
    @DisplayName("Should be instantiable")
    void shouldBeInstantiable() {
        assertDoesNotThrow(MenuesteuerungJpaConfig::new);
    }
}

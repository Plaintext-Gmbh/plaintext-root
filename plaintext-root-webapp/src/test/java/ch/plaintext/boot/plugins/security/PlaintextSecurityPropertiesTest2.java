/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PlaintextSecurityProperties - configuration properties.
 */
class PlaintextSecurityPropertiesTest2 {

    @Test
    void defaultValues_shouldBeEmptyLists() {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();

        assertNotNull(props.getCsrfIgnorePatterns());
        assertTrue(props.getCsrfIgnorePatterns().isEmpty());
        assertNotNull(props.getPermitAllPatterns());
        assertTrue(props.getPermitAllPatterns().isEmpty());
    }

    @Test
    void setCsrfIgnorePatterns_shouldUpdateValue() {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        List<String> patterns = Arrays.asList("/api/**", "/webhook/**");

        props.setCsrfIgnorePatterns(patterns);

        assertEquals(2, props.getCsrfIgnorePatterns().size());
        assertTrue(props.getCsrfIgnorePatterns().contains("/api/**"));
        assertTrue(props.getCsrfIgnorePatterns().contains("/webhook/**"));
    }

    @Test
    void setPermitAllPatterns_shouldUpdateValue() {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        List<String> patterns = Arrays.asList("/public/**", "/health");

        props.setPermitAllPatterns(patterns);

        assertEquals(2, props.getPermitAllPatterns().size());
        assertTrue(props.getPermitAllPatterns().contains("/public/**"));
        assertTrue(props.getPermitAllPatterns().contains("/health"));
    }
}

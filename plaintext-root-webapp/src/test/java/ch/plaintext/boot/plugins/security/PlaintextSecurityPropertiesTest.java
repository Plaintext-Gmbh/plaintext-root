/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PlaintextSecurityProperties - configurable security properties.
 */
class PlaintextSecurityPropertiesTest {

    @Test
    void shouldHaveEmptyDefaultCsrfIgnorePatterns() {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        assertNotNull(props.getCsrfIgnorePatterns());
        assertTrue(props.getCsrfIgnorePatterns().isEmpty());
    }

    @Test
    void shouldHaveEmptyDefaultPermitAllPatterns() {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        assertNotNull(props.getPermitAllPatterns());
        assertTrue(props.getPermitAllPatterns().isEmpty());
    }

    @Test
    void shouldAllowSettingCsrfIgnorePatterns() {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        List<String> patterns = Arrays.asList("/api/**", "/webhook/**");
        props.setCsrfIgnorePatterns(patterns);

        assertEquals(2, props.getCsrfIgnorePatterns().size());
        assertEquals("/api/**", props.getCsrfIgnorePatterns().get(0));
        assertEquals("/webhook/**", props.getCsrfIgnorePatterns().get(1));
    }

    @Test
    void shouldAllowSettingPermitAllPatterns() {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        List<String> patterns = Arrays.asList("/public/**", "/health/**");
        props.setPermitAllPatterns(patterns);

        assertEquals(2, props.getPermitAllPatterns().size());
        assertEquals("/public/**", props.getPermitAllPatterns().get(0));
        assertEquals("/health/**", props.getPermitAllPatterns().get(1));
    }

    @Test
    void rememberMeKey_shouldBeBlankByDefault() {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        assertEquals("", props.getRememberMeKey());
    }

    @Test
    void rememberMeKey_shouldBeConfigurable() {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        props.setRememberMeKey("from-vault-shhhh");

        assertEquals("from-vault-shhhh", props.getRememberMeKey());
    }

    @Test
    void toString_shouldNotBeNull() {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        assertNotNull(props.toString());
    }

    @Test
    void equals_shouldWorkForIdenticalProperties() {
        PlaintextSecurityProperties props1 = new PlaintextSecurityProperties();
        PlaintextSecurityProperties props2 = new PlaintextSecurityProperties();

        assertEquals(props1, props2);
    }

    @Test
    void hashCode_shouldBeConsistentForIdenticalProperties() {
        PlaintextSecurityProperties props1 = new PlaintextSecurityProperties();
        PlaintextSecurityProperties props2 = new PlaintextSecurityProperties();

        assertEquals(props1.hashCode(), props2.hashCode());
    }
}

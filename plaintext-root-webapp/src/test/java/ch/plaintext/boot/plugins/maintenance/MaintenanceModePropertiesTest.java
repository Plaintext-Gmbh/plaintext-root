/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.maintenance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceModePropertiesTest {

    @Test
    void defaultIsDisabled() {
        MaintenanceModeProperties props = new MaintenanceModeProperties();

        assertFalse(props.isEnabled(),
                "Maintenance mode must be off by default");
    }

    @Test
    void defaultBypassRolesIncludeAdminAndRoot() {
        MaintenanceModeProperties props = new MaintenanceModeProperties();

        assertTrue(props.getBypassRoles().contains("ROLE_ADMIN"));
        assertTrue(props.getBypassRoles().contains("ROLE_ROOT"));
    }

    @Test
    void defaultAllowedPathsCoverLoginAndJsfResources() {
        MaintenanceModeProperties props = new MaintenanceModeProperties();
        List<String> defaults = props.getAlwaysAllowedPathPrefixes();

        assertTrue(defaults.contains("/login"));
        assertTrue(defaults.contains("/logout"));
        assertTrue(defaults.contains("/actuator/health"));
        assertTrue(defaults.contains("/jakarta.faces.resource"));
    }

    @Test
    void defaultRetryAfterIs30Minutes() {
        MaintenanceModeProperties props = new MaintenanceModeProperties();

        assertEquals(1800L, props.getRetryAfterSeconds());
    }

    @Test
    void allFieldsAreSettable() {
        MaintenanceModeProperties props = new MaintenanceModeProperties();
        props.setEnabled(true);
        props.setMessage("Custom message");
        props.setBypassRoles(List.of("ROLE_OPS"));
        props.setAlwaysAllowedPathPrefixes(List.of("/api"));
        props.setRetryAfterSeconds(60);

        assertTrue(props.isEnabled());
        assertEquals("Custom message", props.getMessage());
        assertEquals(List.of("ROLE_OPS"), props.getBypassRoles());
        assertEquals(List.of("/api"), props.getAlwaysAllowedPathPrefixes());
        assertEquals(60L, props.getRetryAfterSeconds());
    }
}

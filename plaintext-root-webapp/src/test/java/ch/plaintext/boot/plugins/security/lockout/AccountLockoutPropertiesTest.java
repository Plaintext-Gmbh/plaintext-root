/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.lockout;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AccountLockoutPropertiesTest {

    @Test
    void defaultsAreSensible() {
        AccountLockoutProperties props = new AccountLockoutProperties();

        assertTrue(props.isEnabled());
        assertEquals(5, props.getMaxAttempts());
        assertEquals(Duration.ofMinutes(15), props.getAttemptWindow());
        assertEquals(Duration.ofMinutes(15), props.getLockoutDuration());
    }

    @Test
    void allFieldsAreSettable() {
        AccountLockoutProperties props = new AccountLockoutProperties();
        props.setEnabled(false);
        props.setMaxAttempts(7);
        props.setAttemptWindow(Duration.ofMinutes(5));
        props.setLockoutDuration(Duration.ofMinutes(30));

        assertFalse(props.isEnabled());
        assertEquals(7, props.getMaxAttempts());
        assertEquals(Duration.ofMinutes(5), props.getAttemptWindow());
        assertEquals(Duration.ofMinutes(30), props.getLockoutDuration());
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaintextLoginEventTest {

    // -------------------------------------------------------------------------
    // Constructor with 5 parameters (no requestBaseUrl)
    // -------------------------------------------------------------------------

    @Test
    void constructor_fiveArgs_setsFieldsCorrectly() {
        Object source = new Object();
        PlaintextLoginEvent event = new PlaintextLoginEvent(
                source, "user@example.com", 42L, "Test User", "mandatA");

        assertSame(source, event.getSource());
        assertEquals("user@example.com", event.getUserEmail());
        assertEquals(42L, event.getUserId());
        assertEquals("Test User", event.getUserName());
        assertEquals("mandatA", event.getMandat());
        assertNull(event.getRequestBaseUrl());
    }

    // -------------------------------------------------------------------------
    // Constructor with 6 parameters (with requestBaseUrl)
    // -------------------------------------------------------------------------

    @Test
    void constructor_sixArgs_setsFieldsCorrectly() {
        Object source = new Object();
        PlaintextLoginEvent event = new PlaintextLoginEvent(
                source, "user@example.com", 42L, "Test User", "mandatA", "https://example.com");

        assertSame(source, event.getSource());
        assertEquals("user@example.com", event.getUserEmail());
        assertEquals(42L, event.getUserId());
        assertEquals("Test User", event.getUserName());
        assertEquals("mandatA", event.getMandat());
        assertEquals("https://example.com", event.getRequestBaseUrl());
    }

    // -------------------------------------------------------------------------
    // Null handling
    // -------------------------------------------------------------------------

    @Test
    void constructor_withNullValues_acceptsNulls() {
        Object source = new Object();
        PlaintextLoginEvent event = new PlaintextLoginEvent(
                source, null, null, null, null, null);

        assertNull(event.getUserEmail());
        assertNull(event.getUserId());
        assertNull(event.getUserName());
        assertNull(event.getMandat());
        assertNull(event.getRequestBaseUrl());
    }

    // -------------------------------------------------------------------------
    // ApplicationEvent timestamp
    // -------------------------------------------------------------------------

    @Test
    void constructor_setsTimestamp() {
        long before = System.currentTimeMillis();
        PlaintextLoginEvent event = new PlaintextLoginEvent(
                this, "user@example.com", 1L, "User", "mandat");
        long after = System.currentTimeMillis();

        assertTrue(event.getTimestamp() >= before);
        assertTrue(event.getTimestamp() <= after);
    }
}

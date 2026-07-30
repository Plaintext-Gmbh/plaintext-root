/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.model;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MyRememberMe - the remember-me token entity.
 */
class MyRememberMeTest {

    @Test
    void shouldStoreAndRetrieveAllFields() {
        MyRememberMe token = new MyRememberMe();
        Date now = new Date();

        token.setSeries("test-series");
        token.setUsername("user@test.com");
        token.setToken("token-value");
        token.setLastUsed(now);

        assertEquals("test-series", token.getSeries());
        assertEquals("user@test.com", token.getUsername());
        assertEquals("token-value", token.getToken());
        assertEquals(now, token.getLastUsed());
    }

    @Test
    void shouldHaveNullDefaults() {
        MyRememberMe token = new MyRememberMe();

        assertNull(token.getSeries());
        assertNull(token.getUsername());
        assertNull(token.getToken());
        assertNull(token.getLastUsed());
    }

    @Test
    void equals_shouldWorkCorrectly() {
        MyRememberMe token1 = new MyRememberMe();
        token1.setSeries("series1");
        token1.setUsername("user1");

        MyRememberMe token2 = new MyRememberMe();
        token2.setSeries("series1");
        token2.setUsername("user1");

        assertEquals(token1, token2);
    }

    @Test
    void equals_shouldDetectDifferences() {
        MyRememberMe token1 = new MyRememberMe();
        token1.setSeries("series1");

        MyRememberMe token2 = new MyRememberMe();
        token2.setSeries("series2");

        assertNotEquals(token1, token2);
    }

    @Test
    void toString_shouldContainFields() {
        MyRememberMe token = new MyRememberMe();
        token.setSeries("series1");
        token.setUsername("user@test.com");

        String str = token.toString();
        assertNotNull(str);
        assertTrue(str.contains("series1"));
        assertTrue(str.contains("user@test.com"));
    }
}

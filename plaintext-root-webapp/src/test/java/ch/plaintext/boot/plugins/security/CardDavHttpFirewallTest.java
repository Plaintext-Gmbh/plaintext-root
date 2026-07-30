/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.FirewalledRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests for CardDavHttpFirewall - allowing CardDAV HTTP methods.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CardDavHttpFirewallTest {

    private CardDavHttpFirewall firewall;

    @BeforeEach
    void setUp() {
        firewall = new CardDavHttpFirewall();
    }

    @Test
    void getFirewalledRequest_shouldAllowPropfindMethod() {
        MockHttpServletRequest request = new MockHttpServletRequest("PROPFIND", "/carddav/");

        FirewalledRequest result = firewall.getFirewalledRequest(request);

        assertNotNull(result);
        assertEquals("PROPFIND", result.getMethod());
    }

    @Test
    void getFirewalledRequest_shouldAllowReportMethod() {
        MockHttpServletRequest request = new MockHttpServletRequest("REPORT", "/carddav/");

        FirewalledRequest result = firewall.getFirewalledRequest(request);

        assertNotNull(result);
        assertEquals("REPORT", result.getMethod());
    }

    @Test
    void getFirewalledRequest_shouldAllowCaseInsensitivePropfind() {
        MockHttpServletRequest request = new MockHttpServletRequest("propfind", "/carddav/");

        FirewalledRequest result = firewall.getFirewalledRequest(request);

        assertNotNull(result);
        assertEquals("PROPFIND", result.getMethod());
    }

    @Test
    void getFirewalledRequest_shouldAllowCaseInsensitiveReport() {
        MockHttpServletRequest request = new MockHttpServletRequest("report", "/carddav/");

        FirewalledRequest result = firewall.getFirewalledRequest(request);

        assertNotNull(result);
        assertEquals("REPORT", result.getMethod());
    }

    @Test
    void getFirewalledRequest_shouldDelegateGetMethod() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");

        FirewalledRequest result = firewall.getFirewalledRequest(request);

        assertNotNull(result);
        assertEquals("GET", result.getMethod());
    }

    @Test
    void getFirewalledRequest_shouldDelegatePostMethod() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/data");

        FirewalledRequest result = firewall.getFirewalledRequest(request);

        assertNotNull(result);
        assertEquals("POST", result.getMethod());
    }

    @Test
    void getFirewalledRequest_propfindReset_shouldBeNoOp() {
        MockHttpServletRequest request = new MockHttpServletRequest("PROPFIND", "/carddav/");

        FirewalledRequest result = firewall.getFirewalledRequest(request);

        // reset() should not throw
        assertDoesNotThrow(result::reset);
    }
}

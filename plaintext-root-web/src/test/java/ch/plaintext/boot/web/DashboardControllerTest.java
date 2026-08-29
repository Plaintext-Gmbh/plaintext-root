/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DashboardController
 */
@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @InjectMocks
    private DashboardController controller;

    @Test
    void testDashboard_ShouldRedirectToIndex() {
        // When
        String result = controller.dashboard();

        // Then
        assertNotNull(result);
        assertEquals("redirect:/index.html", result);
    }

    @Test
    void testDashboard_MultipleCalls_ShouldAlwaysReturnSameRedirect() {
        // When
        String result1 = controller.dashboard();
        String result2 = controller.dashboard();
        String result3 = controller.dashboard();

        // Then
        assertEquals("redirect:/index.html", result1);
        assertEquals("redirect:/index.html", result2);
        assertEquals("redirect:/index.html", result3);
    }

    @Test
    void testDashboard_ShouldStartWithRedirectPrefix() {
        // When
        String result = controller.dashboard();

        // Then
        assertTrue(result.startsWith("redirect:"));
    }

    @Test
    void testDashboard_ShouldPointToHtmlFile() {
        // When
        String result = controller.dashboard();

        // Then
        assertTrue(result.endsWith(".html"));
    }
}

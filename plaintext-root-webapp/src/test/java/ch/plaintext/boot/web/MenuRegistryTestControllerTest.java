/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.MenuRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MenuRegistryTestController - menu registry debug page.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MenuRegistryTestControllerTest {

    @Mock
    private MenuRegistry menuRegistry;

    @Mock
    private ApplicationContext applicationContext;

    @InjectMocks
    private MenuRegistryTestController controller;

    @Test
    void testMenuRegistry_shouldReturnHtml_withRegistry() {
        when(menuRegistry.getAllMenuTitles()).thenReturn(Arrays.asList("Home", "Admin"));
        when(menuRegistry.getAllMenuItems()).thenReturn(Collections.emptyList());
        when(applicationContext.getBeansOfType(any())).thenReturn(Collections.emptyMap());

        String result = controller.testMenuRegistry();

        assertNotNull(result);
        assertTrue(result.contains("Menu Registry Test"));
        assertTrue(result.contains("MenuRegistry is available"));
        assertTrue(result.contains("Home"));
        assertTrue(result.contains("Admin"));
    }

    @Test
    void testMenuRegistry_shouldHandleNullRegistry() {
        // Use reflection to set menuRegistry to null
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "menuRegistry", null);
        when(applicationContext.getBeansOfType(any())).thenReturn(Collections.emptyMap());

        String result = controller.testMenuRegistry();

        assertNotNull(result);
        assertTrue(result.contains("MenuRegistry is NULL"));
    }
}

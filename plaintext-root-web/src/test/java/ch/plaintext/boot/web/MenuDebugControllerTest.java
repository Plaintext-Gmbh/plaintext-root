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

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MenuDebugController - menu annotation debug page.
 */
@ExtendWith(MockitoExtension.class)
class MenuDebugControllerTest {

    @Mock
    private MenuRegistry menuRegistry;

    @InjectMocks
    private MenuDebugController controller;

    @Test
    void debugMenuScan_shouldReturnHtml() {
        when(menuRegistry.getAllMenuItems()).thenReturn(Collections.emptyList());

        String result = controller.debugMenuScan();

        assertNotNull(result);
        assertTrue(result.contains("<!DOCTYPE html>"));
        assertTrue(result.contains("Menu Annotation Debug Information"));
    }

    @Test
    void debugMenuScan_shouldContainRegisteredMenuSection() {
        when(menuRegistry.getAllMenuItems()).thenReturn(Collections.emptyList());

        String result = controller.debugMenuScan();

        assertTrue(result.contains("Registered Menu Items"));
    }

    @Test
    void debugMenuScan_shouldContainPackageSection() {
        when(menuRegistry.getAllMenuItems()).thenReturn(Collections.emptyList());

        String result = controller.debugMenuScan();

        assertTrue(result.contains("ch.plaintext"));
    }
}

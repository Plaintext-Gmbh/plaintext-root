/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Extended tests for XhtmlDebugController.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class XhtmlDebugControllerTest2 {

    @Mock
    private ResourcePatternResolver resourcePatternResolver;

    @InjectMocks
    private XhtmlDebugController controller;

    @Test
    void debugXhtmlResources_shouldReturnHtml() throws IOException {
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[0]);

        String result = controller.debugXhtmlResources();

        assertNotNull(result);
        assertTrue(result.contains("<html>"));
        assertTrue(result.contains("XHTML Resources Debug"));
        assertTrue(result.contains("Summary"));
    }

    @Test
    void debugXhtmlResources_shouldHandleIOException() throws IOException {
        when(resourcePatternResolver.getResources(anyString())).thenThrow(new IOException("scan error"));

        String result = controller.debugXhtmlResources();

        assertNotNull(result);
        assertTrue(result.contains("<html>"));
    }

    @Test
    void debugXhtmlResources_shouldShowSearchPatterns() throws IOException {
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[0]);

        String result = controller.debugXhtmlResources();

        assertTrue(result.contains("Search Patterns Used"));
        assertTrue(result.contains("META-INF/resources"));
    }

    @Test
    void debugXhtmlResources_shouldShowSummary() throws IOException {
        when(resourcePatternResolver.getResources(anyString())).thenReturn(new Resource[0]);

        String result = controller.debugXhtmlResources();

        assertTrue(result.contains("Total Resources"));
        assertTrue(result.contains("0"));
    }
}

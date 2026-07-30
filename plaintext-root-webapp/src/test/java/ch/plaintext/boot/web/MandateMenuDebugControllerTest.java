/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MandateMenuDebugController - mandate menu configuration debug page.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MandateMenuDebugControllerTest {

    @Mock
    private MandateMenuConfigRepository repository;

    @InjectMocks
    private MandateMenuDebugController controller;

    @Test
    void debugMandateMenuConfig_shouldReturnHtml_withRepository() {
        when(repository.findAll()).thenReturn(Collections.emptyList());
        when(repository.findByMandateName("default")).thenReturn(Optional.empty());

        String result = controller.debugMandateMenuConfig();

        assertNotNull(result);
        assertTrue(result.contains("Mandate Menu Configuration Debug"));
        assertTrue(result.contains("All Configurations"));
    }

    @Test
    void debugMandateMenuConfig_shouldShowNoConfigs_whenEmpty() {
        when(repository.findAll()).thenReturn(Collections.emptyList());
        when(repository.findByMandateName("default")).thenReturn(Optional.empty());

        String result = controller.debugMandateMenuConfig();

        assertTrue(result.contains("Found 0 configurations"));
    }

    @Test
    void debugMandateMenuConfig_shouldShowTestDefaultMandate() {
        when(repository.findAll()).thenReturn(Collections.emptyList());
        when(repository.findByMandateName("default")).thenReturn(Optional.empty());

        String result = controller.debugMandateMenuConfig();

        assertTrue(result.contains("Test 'default' Mandate"));
        assertTrue(result.contains("No config found for 'default'"));
    }
}

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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for MandateMenuDebugController.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MandateMenuDebugControllerTest2 {

    @Mock
    private MandateMenuConfigRepository repository;

    @InjectMocks
    private MandateMenuDebugController controller;

    @Test
    void debugMandateMenuConfig_shouldReturnHtml_whenRepositoryNull() {
        // Use reflection to set repository to null
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "repository", null);

        String result = controller.debugMandateMenuConfig();

        assertNotNull(result);
        assertTrue(result.contains("Repository is NULL"));
    }

    @Test
    void debugMandateMenuConfig_shouldShowAllConfigs() {
        MandateMenuConfig config = mock(MandateMenuConfig.class);
        when(config.getMandateName()).thenReturn("default");
        when(config.getHiddenMenus()).thenReturn(new HashSet<>(Set.of("Kontakt")));
        when(config.isMenuHidden("Kontakt")).thenReturn(true);
        when(config.isMenuHidden("Kontakte")).thenReturn(false);
        when(config.isMenuHidden("Admin")).thenReturn(false);
        when(config.isMenuHidden("Root")).thenReturn(false);

        when(repository.findAll()).thenReturn(List.of(config));
        when(repository.findByMandateName("default")).thenReturn(Optional.of(config));

        String result = controller.debugMandateMenuConfig();

        assertNotNull(result);
        assertTrue(result.contains("default"));
        assertTrue(result.contains("Kontakt"));
        assertTrue(result.contains("HIDDEN"));
        assertTrue(result.contains("VISIBLE"));
    }

    @Test
    void debugMandateMenuConfig_shouldShowEmptyConfigs() {
        when(repository.findAll()).thenReturn(Collections.emptyList());
        when(repository.findByMandateName("default")).thenReturn(Optional.empty());

        String result = controller.debugMandateMenuConfig();

        assertNotNull(result);
        assertTrue(result.contains("Found 0 configurations"));
        assertTrue(result.contains("No config found for 'default'"));
    }

    @Test
    void debugMandateMenuConfig_shouldHandleNullHiddenMenus() {
        MandateMenuConfig config = mock(MandateMenuConfig.class);
        when(config.getMandateName()).thenReturn("test");
        when(config.getHiddenMenus()).thenReturn(null);

        when(repository.findAll()).thenReturn(List.of(config));
        when(repository.findByMandateName("default")).thenReturn(Optional.empty());

        String result = controller.debugMandateMenuConfig();

        assertNotNull(result);
        assertTrue(result.contains("No hidden menus"));
    }

    @Test
    void debugMandateMenuConfig_shouldHandleEmptyHiddenMenus() {
        MandateMenuConfig config = mock(MandateMenuConfig.class);
        when(config.getMandateName()).thenReturn("test");
        when(config.getHiddenMenus()).thenReturn(new HashSet<>());

        when(repository.findAll()).thenReturn(List.of(config));
        when(repository.findByMandateName("default")).thenReturn(Optional.empty());

        String result = controller.debugMandateMenuConfig();

        assertNotNull(result);
        assertTrue(result.contains("No hidden menus"));
    }
}

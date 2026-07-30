/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.menuesteuerung.service.MandateMenuVisibilityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for DelegatingMenuVisibilityProvider - delegates to MandateMenuVisibilityService.
 */
@ExtendWith(MockitoExtension.class)
class DelegatingMenuVisibilityProviderTest {

    @Mock
    private MandateMenuVisibilityService mandateMenuVisibilityService;

    @InjectMocks
    private DelegatingMenuVisibilityProvider provider;

    @Test
    void isMenuVisible_shouldDelegateToService() {
        when(mandateMenuVisibilityService.isMenuVisible("Kontakte")).thenReturn(true);

        assertTrue(provider.isMenuVisible("Kontakte"));
        verify(mandateMenuVisibilityService).isMenuVisible("Kontakte");
    }

    @Test
    void isMenuVisible_shouldReturnFalse_whenServiceReturnsFalse() {
        when(mandateMenuVisibilityService.isMenuVisible("HiddenMenu")).thenReturn(false);

        assertFalse(provider.isMenuVisible("HiddenMenu"));
    }

    @Test
    void isMenuVisibleForMandate_shouldDelegateToService() {
        when(mandateMenuVisibilityService.isMenuVisibleForMandate("Kontakte", "production")).thenReturn(true);

        assertTrue(provider.isMenuVisibleForMandate("Kontakte", "production"));
        verify(mandateMenuVisibilityService).isMenuVisibleForMandate("Kontakte", "production");
    }

    @Test
    void isMenuVisibleForMandate_shouldReturnFalse_whenHidden() {
        when(mandateMenuVisibilityService.isMenuVisibleForMandate("Admin", "default")).thenReturn(false);

        assertFalse(provider.isMenuVisibleForMandate("Admin", "default"));
    }
}

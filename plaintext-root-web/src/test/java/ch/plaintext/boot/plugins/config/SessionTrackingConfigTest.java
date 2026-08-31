/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.config;

import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionTrackingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.servlet.ServletContextInitializer;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * Test for pinning down the session tracking mode (Card 612).
 */
@ExtendWith(MockitoExtension.class)
class SessionTrackingConfigTest {

    @Mock
    private ServletContext servletContext;

    @Test
    @SuppressWarnings("unchecked")
    void initializer_shouldRestrictSessionTrackingToCookie() throws Exception {
        ServletContextInitializer initializer = new SessionTrackingConfig().sessionTrackingModeInitializer();

        initializer.onStartup(servletContext);

        ArgumentCaptor<Set<SessionTrackingMode>> modes = ArgumentCaptor.forClass(Set.class);
        verify(servletContext).setSessionTrackingModes(modes.capture());

        assertEquals(Set.of(SessionTrackingMode.COOKIE), modes.getValue());
        // The whole point of the card: URL tracking must NOT be among them.
        assertFalse(modes.getValue().contains(SessionTrackingMode.URL));
    }
}

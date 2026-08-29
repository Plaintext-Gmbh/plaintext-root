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
 * Test fuer die Festlegung des Session-Trackings (Karte 612).
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
        // Der Punkt der Karte: URL-Tracking darf NICHT dabei sein.
        assertFalse(modes.getValue().contains(SessionTrackingMode.URL));
    }
}

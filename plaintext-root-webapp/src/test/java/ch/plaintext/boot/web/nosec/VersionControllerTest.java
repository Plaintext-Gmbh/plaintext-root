/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.nosec;

import ch.plaintext.boot.web.VersionBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for VersionController - public version endpoint.
 */
@ExtendWith(MockitoExtension.class)
class VersionControllerTest {

    @Mock
    private VersionBean versionBean;

    @Test
    void getVersion_shouldReturnVersionFromBean() {
        when(versionBean.getVersion()).thenReturn("1.2.3");

        VersionController controller = new VersionController(versionBean);

        assertEquals("1.2.3", controller.getVersion());
        verify(versionBean).getVersion();
    }

    @Test
    void getVersion_shouldReturnNull_whenVersionIsNull() {
        when(versionBean.getVersion()).thenReturn(null);

        VersionController controller = new VersionController(versionBean);

        assertNull(controller.getVersion());
    }
}

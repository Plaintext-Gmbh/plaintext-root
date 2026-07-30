/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class VersionBeanTest {

    @InjectMocks
    private VersionBean versionBean;

    @Test
    void init_shouldSetBuildTimestamp() {
        versionBean.init();
        assertNotNull(versionBean.getBuildTimestamp());
    }

    @Test
    void getFullVersion_shouldCombineVersionAndTimestamp() {
        versionBean.setVersion("1.0.0");
        versionBean.init();

        String fullVersion = versionBean.getFullVersion();
        assertNotNull(fullVersion);
        assertTrue(fullVersion.contains("1.0.0"));
        assertTrue(fullVersion.contains("Build:"));
    }

    @Test
    void getFullVersion_shouldReturnOnlyVersion_whenNoBuildTimestamp() {
        versionBean.setVersion("1.0.0");
        versionBean.setBuildTimestamp(null);

        assertEquals("1.0.0", versionBean.getFullVersion());
    }

    @Test
    void getFullVersion_shouldReturnOnlyVersion_whenEmptyBuildTimestamp() {
        versionBean.setVersion("1.0.0");
        versionBean.setBuildTimestamp("");

        assertEquals("1.0.0", versionBean.getFullVersion());
    }

    @Test
    void settersAndGetters_shouldWork() {
        versionBean.setVersion("2.0.0");
        versionBean.setBuildTimestamp("01.01.24 10:00");
        versionBean.setActiveProfile("test");

        assertEquals("2.0.0", versionBean.getVersion());
        assertEquals("01.01.24 10:00", versionBean.getBuildTimestamp());
        assertEquals("test", versionBean.getActiveProfile());
    }
}

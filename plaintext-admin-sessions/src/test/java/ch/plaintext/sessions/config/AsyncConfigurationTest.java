/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigurationTest {

    @Test
    void classIsAnnotatedWithConfiguration() {
        assertThat(AsyncConfiguration.class.isAnnotationPresent(Configuration.class)).isTrue();
    }

    @Test
    void classIsAnnotatedWithEnableAsync() {
        assertThat(AsyncConfiguration.class.isAnnotationPresent(EnableAsync.class)).isTrue();
    }

    @Test
    void canBeInstantiated() {
        AsyncConfiguration config = new AsyncConfiguration();
        assertThat(config).isNotNull();
    }
}

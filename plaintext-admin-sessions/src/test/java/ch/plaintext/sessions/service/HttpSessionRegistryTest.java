/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.service;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class HttpSessionRegistryTest {

    private HttpSessionRegistry registry;

    @Mock
    private HttpSession session1;

    @Mock
    private HttpSession session2;

    @BeforeEach
    void setUp() {
        registry = new HttpSessionRegistry();
    }

    @Test
    void registerSessionAddsToRegistry() {
        registry.registerSession("sess-1", session1);

        assertThat(registry.getActiveSessionCount()).isEqualTo(1);
        assertThat(registry.getSession("sess-1")).isPresent();
    }

    @Test
    void unregisterSessionRemovesFromRegistry() {
        registry.registerSession("sess-1", session1);
        registry.unregisterSession("sess-1");

        assertThat(registry.getActiveSessionCount()).isZero();
        assertThat(registry.getSession("sess-1")).isEmpty();
    }

    @Test
    void getSessionReturnsEmptyForUnknownId() {
        Optional<HttpSession> result = registry.getSession("nonexistent");
        assertThat(result).isEmpty();
    }

    @Test
    void getSessionReturnsCorrectSession() {
        registry.registerSession("sess-1", session1);
        registry.registerSession("sess-2", session2);

        assertThat(registry.getSession("sess-1")).contains(session1);
        assertThat(registry.getSession("sess-2")).contains(session2);
    }

    @Test
    void getAllSessionIdsReturnsAllRegisteredIds() {
        registry.registerSession("sess-1", session1);
        registry.registerSession("sess-2", session2);

        List<String> ids = registry.getAllSessionIds();
        assertThat(ids).containsExactlyInAnyOrder("sess-1", "sess-2");
    }

    @Test
    void getActiveSessionCountReturnsCorrectCount() {
        assertThat(registry.getActiveSessionCount()).isZero();

        registry.registerSession("sess-1", session1);
        assertThat(registry.getActiveSessionCount()).isEqualTo(1);

        registry.registerSession("sess-2", session2);
        assertThat(registry.getActiveSessionCount()).isEqualTo(2);

        registry.unregisterSession("sess-1");
        assertThat(registry.getActiveSessionCount()).isEqualTo(1);
    }

    @Test
    void registerSessionOverwritesExistingEntry() {
        registry.registerSession("sess-1", session1);
        registry.registerSession("sess-1", session2);

        assertThat(registry.getActiveSessionCount()).isEqualTo(1);
        assertThat(registry.getSession("sess-1")).contains(session2);
    }

    @Test
    void unregisterNonExistentSessionDoesNotThrow() {
        registry.unregisterSession("nonexistent");
        assertThat(registry.getActiveSessionCount()).isZero();
    }
}

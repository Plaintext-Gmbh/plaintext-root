/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class UserSessionTest {

    @Test
    void noArgsConstructorCreatesEmptyEntity() {
        UserSession session = new UserSession();
        assertThat(session.getId()).isNull();
        assertThat(session.getUserId()).isNull();
        assertThat(session.getUsername()).isNull();
        assertThat(session.getSessionId()).isNull();
        assertThat(session.getMandat()).isNull();
        assertThat(session.getActive()).isNull();
    }

    @Test
    void allArgsConstructorPopulatesAllFields() {
        LocalDateTime now = LocalDateTime.now();
        UserSession session = new UserSession(
                1L, 100L, "admin", "sess-123", "mandatA",
                "Mozilla/5.0", now, now, true, now, now
        );

        assertThat(session.getId()).isEqualTo(1L);
        assertThat(session.getUserId()).isEqualTo(100L);
        assertThat(session.getUsername()).isEqualTo("admin");
        assertThat(session.getSessionId()).isEqualTo("sess-123");
        assertThat(session.getMandat()).isEqualTo("mandatA");
        assertThat(session.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(session.getLoginTime()).isEqualTo(now);
        assertThat(session.getLastActivityTime()).isEqualTo(now);
        assertThat(session.getActive()).isTrue();
        assertThat(session.getCreatedDate()).isEqualTo(now);
        assertThat(session.getLastModifiedDate()).isEqualTo(now);
    }

    @Test
    void settersAndGettersWorkCorrectly() {
        UserSession session = new UserSession();
        LocalDateTime now = LocalDateTime.now();

        session.setId(5L);
        session.setUserId(42L);
        session.setUsername("testuser");
        session.setSessionId("abc-def");
        session.setMandat("test-mandat");
        session.setUserAgent("TestAgent/1.0");
        session.setLoginTime(now);
        session.setLastActivityTime(now);
        session.setActive(false);

        assertThat(session.getId()).isEqualTo(5L);
        assertThat(session.getUserId()).isEqualTo(42L);
        assertThat(session.getUsername()).isEqualTo("testuser");
        assertThat(session.getSessionId()).isEqualTo("abc-def");
        assertThat(session.getMandat()).isEqualTo("test-mandat");
        assertThat(session.getUserAgent()).isEqualTo("TestAgent/1.0");
        assertThat(session.getLoginTime()).isEqualTo(now);
        assertThat(session.getLastActivityTime()).isEqualTo(now);
        assertThat(session.getActive()).isFalse();
    }

    @Test
    void equalsAndHashCodeWorkCorrectly() {
        LocalDateTime now = LocalDateTime.now();
        UserSession s1 = new UserSession(1L, 100L, "admin", "sess-1", "m1",
                "ua", now, now, true, now, now);
        UserSession s2 = new UserSession(1L, 100L, "admin", "sess-1", "m1",
                "ua", now, now, true, now, now);

        assertThat(s1).isEqualTo(s2);
        assertThat(s1.hashCode()).isEqualTo(s2.hashCode());
    }

    @Test
    void toStringDoesNotThrow() {
        UserSession session = new UserSession();
        session.setUsername("test");
        assertThat(session.toString()).contains("test");
    }
}

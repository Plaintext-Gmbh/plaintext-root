/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.lockout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AccountLockoutServiceTest {

    private AccountLockoutProperties properties;
    private MutableClock clock;
    private AccountLockoutService service;

    @BeforeEach
    void setUp() {
        properties = new AccountLockoutProperties();
        properties.setEnabled(true);
        properties.setMaxAttempts(3);
        properties.setAttemptWindow(Duration.ofMinutes(10));
        properties.setLockoutDuration(Duration.ofMinutes(15));

        clock = new MutableClock(Instant.parse("2026-05-04T20:00:00Z"));
        service = new AccountLockoutService(properties, clock);
    }

    @Test
    void freshAccountIsNotLocked() {
        assertFalse(service.isLocked("alice"));
    }

    @Test
    void singleFailureDoesNotLock() {
        service.recordFailure("alice");

        assertFalse(service.isLocked("alice"));
        assertEquals(1, service.attemptInfo("alice").orElseThrow().counter());
    }

    @Test
    void exactlyMaxAttemptsTriggersLock() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");

        assertTrue(service.isLocked("alice"));
    }

    @Test
    void successResetsCounter() {
        service.recordFailure("alice");
        service.recordFailure("alice");

        service.recordSuccess("alice");

        assertFalse(service.isLocked("alice"));
        assertTrue(service.attemptInfo("alice").isEmpty());
    }

    @Test
    void manualResetClearsLock() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");
        assertTrue(service.isLocked("alice"));

        service.reset("alice");

        assertFalse(service.isLocked("alice"));
    }

    @Test
    void slidingWindow_failuresOlderThanWindowResetCounter() {
        service.recordFailure("alice");
        service.recordFailure("alice");

        // 11 minutes later — outside the 10-minute window
        clock.advance(Duration.ofMinutes(11));

        service.recordFailure("alice");

        // Counter should have reset to 1
        assertEquals(1, service.attemptInfo("alice").orElseThrow().counter());
        assertFalse(service.isLocked("alice"));
    }

    @Test
    void lockoutAutoUnlocksAfterDuration() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");
        assertTrue(service.isLocked("alice"));

        clock.advance(Duration.ofMinutes(16)); // > 15 min lockout duration

        assertFalse(service.isLocked("alice"));
        // After auto-unlock the record is cleared so a fresh window starts
        assertTrue(service.attemptInfo("alice").isEmpty());
    }

    @Test
    void disabledFlagDisablesEverything() {
        properties.setEnabled(false);

        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");

        assertFalse(service.isLocked("alice"),
                "Service must always report unlocked when enabled=false");
    }

    @Test
    void usernameComparisonIsCaseInsensitive() {
        service.recordFailure("Alice");
        service.recordFailure("alice");
        service.recordFailure("ALICE");

        assertTrue(service.isLocked("alice"));
        assertTrue(service.isLocked("Alice"));
    }

    @Test
    void nullAndBlankUsernamesAreNoOp() {
        assertDoesNotThrow(() -> service.recordFailure(null));
        assertDoesNotThrow(() -> service.recordFailure(""));
        assertDoesNotThrow(() -> service.recordFailure("  "));
        assertDoesNotThrow(() -> service.recordSuccess(null));
        assertDoesNotThrow(() -> service.reset(null));

        assertFalse(service.isLocked(null));
        assertFalse(service.isLocked(""));
        assertFalse(service.isLocked("  "));
    }

    @Test
    void lockoutIsPerUsername() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");

        assertTrue(service.isLocked("alice"));
        assertFalse(service.isLocked("bob"));
    }

    /** Test clock that allows {@link #advance(Duration)}. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        void advance(Duration delta) {
            now.updateAndGet(t -> t.plus(delta));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}

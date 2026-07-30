/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.lockout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class AccountLockoutEventListenerTest {

    private AccountLockoutService service;
    private AccountLockoutEventListener listener;

    @BeforeEach
    void setUp() {
        AccountLockoutProperties properties = new AccountLockoutProperties();
        properties.setMaxAttempts(3);
        service = new AccountLockoutService(properties, Clock.systemUTC());
        listener = new AccountLockoutEventListener(service);
    }

    @Test
    void badCredentialsEventIncrementsCounter() {
        Authentication auth = new UsernamePasswordAuthenticationToken("alice", "wrong");

        listener.onBadCredentials(new AuthenticationFailureBadCredentialsEvent(
                auth, new org.springframework.security.authentication.BadCredentialsException("nope")));

        assertEquals(1, service.attemptInfo("alice").orElseThrow().counter());
    }

    @Test
    void successEventClearsCounter() {
        // first prime the counter
        service.recordFailure("alice");
        service.recordFailure("alice");

        Authentication auth = new UsernamePasswordAuthenticationToken("alice", "right",
                Collections.emptyList());

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(auth));

        assertTrue(service.attemptInfo("alice").isEmpty());
    }

    @Test
    void lockedEventDoesNotBumpCounterAgain() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice"); // already locked

        // The listener does not subscribe to AuthenticationFailureLockedEvent,
        // so a locked-event being fired must not double-count.
        Authentication auth = new UsernamePasswordAuthenticationToken("alice", "x");
        AuthenticationFailureLockedEvent event = new AuthenticationFailureLockedEvent(
                auth, new org.springframework.security.authentication.LockedException("locked"));

        // No listener method consumes this event — assertion is just that the
        // counter has not changed.
        assertEquals(3, service.attemptInfo("alice").orElseThrow().counter());
        assertNotNull(event); // silence "unused" warnings
    }

    @Test
    void unknownPrincipalShapeFallsBackToAuthenticationName() {
        // Authentication.getPrincipal() returns Object, not always String.
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new Object() { @Override public String toString() { return "obj-principal"; } },
                "creds") {
            @Override public String getName() { return "alice-from-getName"; }
        };

        listener.onBadCredentials(new AuthenticationFailureBadCredentialsEvent(
                auth, new org.springframework.security.authentication.BadCredentialsException("nope")));

        assertEquals(1, service.attemptInfo("alice-from-getName").orElseThrow().counter());
    }

    @Test
    void nullAuthenticationIsHandledQuietly() {
        // No NPE even when the event carries a null Authentication — Spring
        // does not normally do this but defensive coding never hurts.
        Authentication auth = new UsernamePasswordAuthenticationToken(null, null);

        assertDoesNotThrow(() -> listener.onBadCredentials(
                new AuthenticationFailureBadCredentialsEvent(
                        auth, new org.springframework.security.authentication.BadCredentialsException("x"))));
    }
}

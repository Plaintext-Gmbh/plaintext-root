/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RateLimiter")
class RateLimiterTest {

    @Test
    void shouldAllowRequestsWithinLimit() {
        RateLimiter limiter = new RateLimiter(5, 60000);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryConsume("user1"));
        }
    }

    @Test
    void shouldBlockRequestsOverLimit() {
        RateLimiter limiter = new RateLimiter(3, 60000);
        assertTrue(limiter.tryConsume("user1"));
        assertTrue(limiter.tryConsume("user1"));
        assertTrue(limiter.tryConsume("user1"));
        assertFalse(limiter.tryConsume("user1"));
    }

    @Test
    void shouldTrackDifferentKeysIndependently() {
        RateLimiter limiter = new RateLimiter(2, 60000);
        assertTrue(limiter.tryConsume("user1"));
        assertTrue(limiter.tryConsume("user1"));
        assertFalse(limiter.tryConsume("user1"));

        // user2 should still have full quota
        assertTrue(limiter.tryConsume("user2"));
        assertTrue(limiter.tryConsume("user2"));
        assertFalse(limiter.tryConsume("user2"));
    }

    @Test
    void shouldReturnCorrectRemainingRequests() {
        RateLimiter limiter = new RateLimiter(5, 60000);
        assertEquals(5, limiter.getRemainingRequests("user1"));
        limiter.tryConsume("user1");
        assertEquals(4, limiter.getRemainingRequests("user1"));
    }

    @Test
    void shouldReturnMaxForUnknownKey() {
        RateLimiter limiter = new RateLimiter(10, 60000);
        assertEquals(10, limiter.getRemainingRequests("unknown"));
    }

    @Test
    void shouldRefillAfterWindowExpires() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(2, 100); // 100ms window
        assertTrue(limiter.tryConsume("user1"));
        assertTrue(limiter.tryConsume("user1"));
        assertFalse(limiter.tryConsume("user1"));

        Thread.sleep(150); // Wait for window to expire

        assertTrue(limiter.tryConsume("user1")); // Should be refilled
    }

    @Test
    void shouldTrackBucketCount() {
        RateLimiter limiter = new RateLimiter(5, 60000);
        assertEquals(0, limiter.size());
        limiter.tryConsume("user1");
        assertEquals(1, limiter.size());
        limiter.tryConsume("user2");
        assertEquals(2, limiter.size());
    }

    @Test
    void shouldCleanupExpiredBuckets() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(5, 50); // 50ms window
        limiter.tryConsume("user1");
        limiter.tryConsume("user2");
        assertEquals(2, limiter.size());

        Thread.sleep(150); // Wait for expiration (2x window)

        limiter.cleanup();
        assertEquals(0, limiter.size());
    }

    @Test
    void shouldNotCleanupActiveBuckets() {
        RateLimiter limiter = new RateLimiter(5, 60000);
        limiter.tryConsume("user1");
        limiter.cleanup();
        assertEquals(1, limiter.size());
    }

    @Test
    void shouldHandleSingleRequestLimit() {
        RateLimiter limiter = new RateLimiter(1, 60000);
        assertTrue(limiter.tryConsume("user1"));
        assertFalse(limiter.tryConsume("user1"));
    }

    @Test
    void shouldHandleHighConcurrency() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(100, 60000);
        Thread[] threads = new Thread[10];
        int[] consumed = new int[1];

        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 20; j++) {
                    if (limiter.tryConsume("shared")) {
                        synchronized (consumed) {
                            consumed[0]++;
                        }
                    }
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        assertEquals(100, consumed[0]); // Exactly 100 should pass
    }
}

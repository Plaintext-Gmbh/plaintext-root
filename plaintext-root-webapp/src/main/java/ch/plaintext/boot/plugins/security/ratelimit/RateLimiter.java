/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple in-memory rate limiter using the token bucket algorithm.
 * Each key (e.g., IP address or user) gets a bucket with a configurable
 * capacity and refill rate.
 */
public class RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param maxRequests Maximum requests allowed per window
     * @param windowMillis Time window in milliseconds
     */
    public RateLimiter(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    /**
     * Check if a request from the given key is allowed.
     *
     * @param key Identifier (e.g., IP address, username)
     * @return true if allowed, false if rate limited
     */
    public boolean tryConsume(String key) {
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(maxRequests, windowMillis));
        return bucket.tryConsume();
    }

    /**
     * Get remaining requests for a key.
     */
    public int getRemainingRequests(String key) {
        TokenBucket bucket = buckets.get(key);
        if (bucket == null) return maxRequests;
        return bucket.getRemaining();
    }

    /**
     * Clean up expired buckets to prevent memory leaks.
     * Should be called periodically.
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(entry -> entry.getValue().isExpired(now, windowMillis * 2));
    }

    public int size() {
        return buckets.size();
    }

    private static class TokenBucket {
        private final int capacity;
        private final long windowMillis;
        private final AtomicLong tokens;
        private volatile long lastRefillTime;

        TokenBucket(int capacity, long windowMillis) {
            this.capacity = capacity;
            this.windowMillis = windowMillis;
            this.tokens = new AtomicLong(capacity);
            this.lastRefillTime = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            refill();
            long current = tokens.get();
            if (current > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        }

        int getRemaining() {
            refill();
            return (int) Math.max(0, tokens.get());
        }

        boolean isExpired(long now, long expirationMillis) {
            return (now - lastRefillTime) > expirationMillis;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            if (elapsed >= windowMillis) {
                tokens.set(capacity);
                lastRefillTime = now;
            }
        }
    }
}

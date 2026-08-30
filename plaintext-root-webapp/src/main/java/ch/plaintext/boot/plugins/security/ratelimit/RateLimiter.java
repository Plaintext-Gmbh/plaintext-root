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

    /**
     * Default upper limit for the number of buckets held simultaneously.
     * SECURITY (card 303, finding 2, addendum): without a cap an attacker with many
     * different keys could make the map grow without bound (memory DoS), because the
     * cleanup only runs every 5 minutes.
     */
    public static final int DEFAULT_MAX_BUCKETS = 50_000;

    /**
     * Collective key, once the cap is reached. New keys no longer get a bucket of their
     * own but share this single one — the flood thereby throttles itself,
     * while already known (legitimate) keys keep their own bucket.
     */
    static final String OVERFLOW_KEY = "__overflow__";

    private final int maxRequests;
    private final long windowMillis;
    private final int maxBuckets;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param maxRequests Maximum requests allowed per window
     * @param windowMillis Time window in milliseconds
     */
    public RateLimiter(int maxRequests, long windowMillis) {
        this(maxRequests, windowMillis, DEFAULT_MAX_BUCKETS);
    }

    public RateLimiter(int maxRequests, long windowMillis, int maxBuckets) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
        this.maxBuckets = maxBuckets;
    }

    /**
     * Check if a request from the given key is allowed.
     *
     * @param key Identifier (e.g., IP address, username)
     * @return true if allowed, false if rate limited
     */
    public boolean tryConsume(String key) {
        TokenBucket bucket = bucketFor(key);
        return bucket.tryConsume();
    }

    private TokenBucket bucketFor(String key) {
        TokenBucket existing = buckets.get(key);
        if (existing != null) {
            return existing;
        }
        String effectiveKey = buckets.size() >= maxBuckets ? OVERFLOW_KEY : key;
        return buckets.computeIfAbsent(effectiveKey, k -> new TokenBucket(maxRequests, windowMillis));
    }

    /**
     * Returns a previously consumed token (at most up to the capacity).
     * Used for login attempts that turn out to be successful after the fact:
     * the limit is meant to slow down guessing attempts, not real logins.
     */
    public void refund(String key) {
        TokenBucket bucket = buckets.get(key);
        if (bucket != null) {
            bucket.refund();
        }
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

        synchronized void refund() {
            refill();
            if (tokens.get() < capacity) {
                tokens.incrementAndGet();
            }
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

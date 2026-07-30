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
     * Default-Obergrenze fuer die Anzahl gleichzeitig gehaltener Buckets.
     * SECURITY (Karte 303, Befund 2, Zusatz): Ohne Deckel konnte ein Angreifer mit vielen
     * verschiedenen Schluesseln die Map unbegrenzt wachsen lassen (Speicher-DoS), weil das
     * Cleanup nur alle 5 Minuten laeuft.
     */
    public static final int DEFAULT_MAX_BUCKETS = 50_000;

    /**
     * Sammel-Schluessel, sobald der Deckel erreicht ist. Neue Schluessel bekommen keinen eigenen
     * Bucket mehr, sondern teilen sich diesen einen — der Flood bremst damit sich selbst aus,
     * waehrend bereits bekannte (legitime) Schluessel ihren eigenen Bucket behalten.
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
     * Gibt ein zuvor verbrauchtes Token zurueck (maximal bis zur Kapazitaet).
     * Wird fuer Login-Versuche benutzt, die sich nachtraeglich als erfolgreich herausstellen:
     * das Limit soll Rateversuche bremsen, nicht echte Anmeldungen.
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

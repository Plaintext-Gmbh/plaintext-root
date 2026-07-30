/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.lockout;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks failed-login attempts per username and decides whether an account
 * should currently be locked out.
 *
 * <p>State is held in memory in a {@link ConcurrentHashMap}. We accept the
 * trade-off:
 * <ul>
 *   <li>The map cannot grow unbounded in any realistic deployment — there
 *       is one entry per username that has at least one recent failure,
 *       and entries reset on success.</li>
 *   <li>Lockouts do not survive restarts. That's acceptable: a brute-force
 *       attacker triggering a restart would just be hitting a throttle
 *       elsewhere, and the sliding window naturally heals after
 *       {@link AccountLockoutProperties#getAttemptWindow()}.</li>
 *   <li>Per-IP and global rate limits are tracked separately (see
 *       {@code IMPROVEMENT_PROPOSALS.md} → "Per-tenant rate limiting").</li>
 * </ul>
 */
@Service
@Slf4j
public class AccountLockoutService {

    private final AccountLockoutProperties properties;
    private final Clock clock;
    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    @Autowired
    public AccountLockoutService(AccountLockoutProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AccountLockoutService(AccountLockoutProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Record a single failed login attempt. Increments the counter for
     * {@code username}, opening a fresh sliding window if the previous one
     * has expired.
     */
    public void recordFailure(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        attempts.compute(normalize(username), (key, existing) -> {
            Instant now = clock.instant();
            if (existing == null
                    || isOutsideWindow(existing.firstFailure(), now,
                            properties.getAttemptWindow())) {
                return new AttemptRecord(1, now, now);
            }
            return new AttemptRecord(existing.counter() + 1,
                    existing.firstFailure(), now);
        });
        if (log.isDebugEnabled()) {
            attemptInfo(username).ifPresent(info ->
                    log.debug("recordFailure(username='{}') counter={} firstFailure={}",
                            username, info.counter(), info.firstFailure()));
        }
    }

    /**
     * Successful authentication — clear the failure counter so a normal
     * user is not surprised by an old leftover record.
     */
    public void recordSuccess(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        attempts.remove(normalize(username));
    }

    /**
     * Whether {@code username} is currently locked out. Always {@code false}
     * when the feature is disabled.
     */
    public boolean isLocked(String username) {
        if (!properties.isEnabled() || username == null || username.isBlank()) {
            return false;
        }
        AttemptRecord record = attempts.get(normalize(username));
        if (record == null) {
            return false;
        }
        if (record.counter() < properties.getMaxAttempts()) {
            return false;
        }
        Instant lockedUntil = record.lastFailure()
                .plus(properties.getLockoutDuration());
        if (clock.instant().isAfter(lockedUntil)) {
            // Auto-unlock — drop the record so the next failure starts a
            // fresh window.
            attempts.remove(normalize(username), record);
            return false;
        }
        return true;
    }

    /**
     * Manual reset (for an operator-facing admin endpoint). No-op when the
     * username has no record.
     */
    public void reset(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        attempts.remove(normalize(username));
    }

    /**
     * Read-only snapshot of the current record for {@code username}. Used by
     * tests and admin diagnostics.
     */
    public Optional<AttemptRecord> attemptInfo(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(attempts.get(normalize(username)));
    }

    private static boolean isOutsideWindow(Instant first, Instant now, Duration window) {
        return Duration.between(first, now).compareTo(window) > 0;
    }

    private static String normalize(String username) {
        return username.toLowerCase();
    }

    /**
     * Snapshot of failure attempts for one username.
     */
    public record AttemptRecord(int counter, Instant firstFailure, Instant lastFailure) {}
}

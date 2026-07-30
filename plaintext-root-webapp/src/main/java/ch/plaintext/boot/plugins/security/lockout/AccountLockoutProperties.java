/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.lockout;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration for {@link AccountLockoutService}.
 *
 * <p>All properties live under {@code plaintext.security.lockout} (env:
 * {@code PLAINTEXT_SECURITY_LOCKOUT_*}).
 */
@Component
@ConfigurationProperties(prefix = "plaintext.security.lockout")
@Data
public class AccountLockoutProperties {

    /**
     * Master switch — set to {@code false} to disable the lockout entirely
     * (useful for tests or for one-off operator overrides).
     */
    private boolean enabled = true;

    /**
     * Number of bad-credentials attempts within {@link #attemptWindow} that
     * triggers a lockout. Default: 5.
     */
    private int maxAttempts = 5;

    /**
     * Sliding window over which failed attempts are counted. Default: 15 min.
     */
    private Duration attemptWindow = Duration.ofMinutes(15);

    /**
     * How long an account stays locked once {@link #maxAttempts} is reached.
     * Default: 15 min.
     */
    private Duration lockoutDuration = Duration.ofMinutes(15);
}

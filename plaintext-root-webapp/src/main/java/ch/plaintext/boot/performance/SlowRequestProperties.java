/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.performance;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings of the slowness detection (card 430).
 *
 * <p>Deliberately configurable instead of hard-wired: what "slow" means differs per
 * application and endpoint. A file upload may take seconds, an Ajax postback may not.
 *
 * @author plaintext.ch
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "plaintext.performance.slow-request")
public class SlowRequestProperties {

    /** Emergency off switch. On {@code false} the filter measures nothing and logs nothing. */
    private boolean enabled = true;

    /** From this duration on a request counts as slow and is logged at WARN. */
    private Duration schwelle = Duration.ofSeconds(2);

    /**
     * Minimum interval between two WARN messages for **the same path**. If an application is
     * permanently slow, a line would otherwise be produced on every request — the log would be
     * unreadable and the actual information would drown. Suppressed hits are counted and reported
     * with the next message, so that nothing disappears silently.
     */
    private Duration meldeabstand = Duration.ofMinutes(1);

    /**
     * Upper limit of the observed paths. Protects against unbounded growth when paths are
     * variable; beyond that limit messages are only emitted, without throttling.
     */
    private int maxPfade = 500;
}

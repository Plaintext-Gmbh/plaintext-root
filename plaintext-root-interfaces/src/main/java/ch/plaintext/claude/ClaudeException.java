/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude;

import java.time.Duration;

/** A failed call to Claude; {@link #art()} tells whether waiting and retrying can help. */
public class ClaudeException extends RuntimeException {

    /** Kind of failure. */
    public enum Art {
        /** No API key configured. */
        NICHT_KONFIGURIERT,
        /** Key rejected (401/403). */
        AUTH,
        /** Rate limit exhausted (429) even after the retries. */
        RATE_LIMIT,
        /** API overloaded (529) or a server error (5xx). */
        UEBERLASTET,
        /** Connection failed or timed out. */
        NETZ,
        /** Anything else, e.g. a 400 for an invalid request. */
        ANDERE
    }

    private final Art art;
    private final Duration retryAfter;

    public ClaudeException(Art art, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.art = art;
        this.retryAfter = retryAfter;
    }

    public ClaudeException(Art art, String message) {
        this(art, message, null, null);
    }

    /** Kind of failure. */
    public Art art() {
        return art;
    }

    /** Wait time the API asked for ({@code retry-after}), or {@code null}. */
    public Duration retryAfter() {
        return retryAfter;
    }
}

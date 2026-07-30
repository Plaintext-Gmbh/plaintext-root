/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.apitoken;

import java.time.Instant;

/**
 * Interface for standardized API error responses (RFC 7807).
 * Implementations are serialized to JSON in REST responses.
 */
public interface IApiErrorResponse {

    /**
     * Gets the HTTP status code.
     *
     * @return the HTTP status code (e.g. 401, 403)
     */
    int getStatus();

    /**
     * Gets the error type description.
     *
     * @return the error type (e.g. "Unauthorized", "Forbidden")
     */
    String getError();

    /**
     * Gets the human-readable error message.
     *
     * @return the error message
     */
    String getMessage();

    /**
     * Gets the timestamp when the error occurred.
     *
     * @return the error timestamp
     */
    Instant getTimestamp();

    /**
     * Gets the expiration time of the token, if applicable.
     *
     * @return the token expiration instant, or null if not an expiration error
     */
    Instant getExpiredAt();

    /**
     * Gets the request path that caused the error.
     *
     * @return the request URI path
     */
    String getPath();
}

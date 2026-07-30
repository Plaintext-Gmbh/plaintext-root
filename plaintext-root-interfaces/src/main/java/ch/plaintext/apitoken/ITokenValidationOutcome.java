/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.apitoken;

import org.springframework.http.ResponseEntity;

/**
 * Outcome of a Bearer token validation.
 * Either contains an error response (token missing/invalid/expired/revoked)
 * or a successful validation result.
 */
public interface ITokenValidationOutcome {

    /**
     * Checks whether the token validation resulted in an error.
     *
     * @return true if the token is missing, invalid, expired, or revoked
     */
    boolean hasError();

    /**
     * Gets the error response entity to return to the client.
     * Should only be called when {@link #hasError()} returns true.
     *
     * @return the error response entity with appropriate HTTP status and body
     */
    ResponseEntity<?> getErrorResponse();

    /**
     * Gets the successful validation result.
     * Should only be called when {@link #hasError()} returns false.
     *
     * @return the token validation result containing user and token metadata
     */
    IApiTokenService.ApiTokenValidationResult getValidation();
}

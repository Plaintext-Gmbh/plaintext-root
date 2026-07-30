/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.apitoken;

/**
 * Service for validating Bearer tokens in REST API requests.
 * Extracts and validates JWT tokens from the Authorization header.
 * <p>
 * Usage in controllers:
 * <pre>
 * ITokenValidationOutcome outcome = apiTokenValidatorService.validateRequest(authHeader, requestPath);
 * if (outcome.hasError()) return outcome.getErrorResponse();
 * Long userId = outcome.getValidation().userId();
 * </pre>
 */
public interface ApiTokenValidatorService {

    /**
     * Validate a Bearer token from the Authorization header.
     *
     * @param authorizationHeader the full Authorization header value (e.g. "Bearer eyJ...")
     * @param requestPath         the request URI path (for error messages)
     * @return validation outcome with either error response or successful validation result
     */
    ITokenValidationOutcome validateRequest(String authorizationHeader, String requestPath);
}

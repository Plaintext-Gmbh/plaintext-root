/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.apitoken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service interface for API token management.
 * Allows modules to create and validate API tokens without
 * depending on the implementation module.
 */
public interface IApiTokenService {

    /**
     * Create a new API token for a user.
     * The returned JWT string is only available once - it is not stored in the database.
     *
     * @param userId       User ID
     * @param mandat       Mandat identifier
     * @param tokenName    User-defined name for this token
     * @param email        User's email address
     * @param validityDays Token validity in days (7-90)
     * @return The JWT token string (one-time, not recoverable)
     */
    String createToken(Long userId, String mandat, String tokenName, String email, int validityDays);

    /**
     * Validate a JWT token.
     * Checks RSA signature (PKI), expiration, and revocation status.
     *
     * @param jwtToken The JWT token string
     * @return Validation result with userId/mandat, or empty if invalid/expired/revoked
     */
    Optional<ApiTokenValidationResult> validateToken(String jwtToken);

    /**
     * Invalidate a token (soft-delete). The token cannot be deleted, only invalidated.
     *
     * @param tokenId Token database ID
     * @param userId  Owner user ID
     * @param mandat  Mandat identifier
     */
    void invalidateToken(Long tokenId, Long userId, String mandat);

    /**
     * Invalidate a token by admin (any user in mandat).
     *
     * @param tokenId Token database ID
     * @param mandat  Mandat identifier
     */
    void invalidateTokenByAdmin(Long tokenId, String mandat);

    /**
     * Invalidate a token by root (no mandat restriction).
     *
     * @param tokenId Token database ID
     */
    void invalidateTokenByRoot(Long tokenId);

    /**
     * Get names of all active (non-expired, non-invalidated) tokens for a user.
     *
     * @param userId the user ID
     * @param mandat the mandate identifier
     * @return list of active token names
     */
    List<String> getActiveTokenNames(Long userId, String mandat);

    /**
     * Check if a token with the given name is still active (not expired, not invalidated).
     *
     * @param tokenName the token name to check
     * @param mandat    the mandate identifier
     * @return true if the token is active, false otherwise
     */
    boolean isTokenActiveByName(String tokenName, String mandat);

    /**
     * Result of a successful API token validation, containing the user and token metadata.
     *
     * @param userId    the ID of the user who owns the token
     * @param mandat    the mandate identifier
     * @param email     the email address associated with the token
     * @param tokenName the user-defined name of the token
     * @param expiresAt the expiration time of the token
     */
    record ApiTokenValidationResult(Long userId, String mandat, String email, String tokenName, Instant expiresAt) {}
}

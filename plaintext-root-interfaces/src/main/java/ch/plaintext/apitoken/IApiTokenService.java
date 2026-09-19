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
     * Name prefix marking a token as a <b>browser credential</b> — issued for a page of this
     * application, never for the API.
     *
     * <p><b>What it is for (card 1257).</b> The watch phone link is a signed token in a URL that
     * anybody who gets the link can use. It is meant for the watch pages and nothing else. Its
     * {@code scope} cannot say that: the scope ladder knows only READ/EINTRAGEN/ADMIN, and an
     * unknown value falls back to READ — which at {@code /mcp} is still read access to the whole
     * application with the owner's roles. A leaked watch link would thereby hand out the owner's
     * mail, contacts and records.</p>
     *
     * <p>{@code McpBearerTokenFilter} therefore rejects every token whose name starts with this
     * prefix, regardless of scope. The token stays what it is on its own page — the page path
     * itself is guarded separately — and is worth nothing at the API.</p>
     *
     * <p>Case-insensitive on purpose: the name is typed by hand in several places.</p>
     */
    String UI_TOKEN_NAME_PREFIX = "ui:";

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
     * Create a new API token <b>with an explicit permission scope</b>.
     *
     * <p><b>Why this belongs in the interface (card 1257).</b> The overload above cannot say
     * what the token may do; a token without a {@code scope} claim is read by
     * {@code McpBearerTokenFilter} as its fail-closed default, and by an older configuration as
     * {@code ADMIN} (card 312). A module outside {@code plaintext-admin-apitoken} therefore had
     * no way at all to issue a token that is narrower than the default — it could only ask for
     * one and hope. The watch phone link needs exactly that: the least a token can carry.</p>
     *
     * <p><b>Deliberately a default that throws</b>, like
     * {@link #invalidateTokensByName(Long, String, String)}: a default that quietly dropped the
     * scope would issue a <em>wider</em> token than the caller asked for, and nothing would
     * say so.</p>
     *
     * @param scope {@code READ}, {@code EINTRAGEN} or {@code ADMIN}; {@code null}/empty omits
     *              the claim and thereby falls back to the filter's default
     * @return the JWT token string (one-time, not recoverable)
     * @throws UnsupportedOperationException if the implementation does not support it
     */
    default String createToken(Long userId, String mandat, String tokenName, String email, int validityDays,
                               String scope) {
        throw new UnsupportedOperationException(
                "createToken mit scope wird von " + getClass().getName() + " nicht unterstuetzt; "
                        + "ohne scope waere der Token weiter als bestellt");
    }

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
     * Invalidates <b>every</b> active token of this user with this name.
     *
     * <p><b>Why this exists (card 1257).</b> {@code createServiceToken} deliberately leaves the
     * previous token of the same flow alive — two devices of the same user have to be able to
     * work at the same time. For a personal link that is exactly wrong: "generate a new one" has
     * to make the old one invalid on the spot, otherwise a link handed out once can never be
     * taken back. The caller therefore revokes first and issues afterwards.</p>
     *
     * <p><b>Deliberately a default that throws.</b> The house has exactly one implementation
     * ({@code ApiTokenService}); a default returning 0 would let a second implementation report
     * a successful revocation while revoking nothing, and a caller would go on to hand out a new
     * link in the belief that the old one is dead. A missing revocation must be loud.</p>
     *
     * @param userId    owner
     * @param mandat    tenant
     * @param tokenName exact name, as passed when issuing
     * @return how many tokens were invalidated
     * @throws UnsupportedOperationException if the implementation does not support it
     */
    default int invalidateTokensByName(Long userId, String mandat, String tokenName) {
        throw new UnsupportedOperationException(
                "invalidateTokensByName wird von " + getClass().getName() + " nicht unterstuetzt; "
                        + "ohne sofortigen Widerruf darf kein neuer Link ausgegeben werden");
    }

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
     * @param scope     permission scope from the {@code scope} claim ({@code READ}/{@code EINTRAGEN}/
     *                  {@code ADMIN}/{@code SESSION}), or {@code null} for legacy tokens without the claim.
     *                  <b>Card 309:</b> up to this point the claim was read by {@code JwtValidationResult}
     *                  but never passed on — callers outside the MCP filter (for example
     *                  {@code TokenLoginController}) therefore had no way to evaluate the scope
     *                  restriction and blanket-granted the full DB roles of the token owner.
     */
    record ApiTokenValidationResult(Long userId, String mandat, String email, String tokenName, Instant expiresAt,
                                    String scope) {

        /** Convenience constructor for callers without scope information (legacy behavior). */
        public ApiTokenValidationResult(Long userId, String mandat, String email, String tokenName, Instant expiresAt) {
            this(userId, mandat, email, tokenName, expiresAt, null);
        }
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.apitoken;

import java.util.Optional;

/**
 * Narrow, leak-free access to {@code api_token} for the revocation check in the
 * MCP bearer filter.
 *
 * <p><b>Why this interface exists (card 659, out of 655):</b> {@code spring.jpa.open-in-view}
 * is left at the Spring Boot default {@code true} (switched off in none of the repos), and the
 * {@code OpenEntityManagerInViewFilter} wraps the entire security filter chain. The first
 * JPA access from within the filter therefore binds an EntityManager to the request and holds its
 * DB connection until the end of the request. For an MCP session
 * ({@code spring.ai.mcp.server.protocol: STREAMABLE}) that is the <b>whole session duration</b>;
 * after 60 seconds HikariCP reports the connection as {@code Apparent connection leak detected}.
 * Measured in PROD: 15 such warnings in 7 days, with {@code ApiTokenService} in the stack.
 *
 * <p>The same cause has already been fixed for the role lookup in the same filter
 * ({@link ch.plaintext.McpUserRoles}, card 437) — there as here it holds: a single
 * JDBC access opens the connection, reads and returns it immediately, without an EntityManager
 * that would stay attached to the request.
 *
 * <p><b>Two methods, not one:</b> the best-effort write to {@code last_used_at} /
 * {@code use_count} is the second JPA access in the same path and binds the session just as much.
 * A rework that only converts the lookup moves the problem two lines further along.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public interface ApiTokenRevocationLookup {

    /**
     * Reads the fields that {@code ApiTokenService.validateVerifiedToken} needs for the
     * revocation decision.
     *
     * @param tokenHash SHA-256 hash of the JWT (hex, 64 characters); the column is unique
     * @return the state of the token, or empty if no record exists for this hash
     *         (= revoked or never issued)
     */
    Optional<TokenZustand> findForValidation(String tokenHash);

    /**
     * Card 664: has the token with this {@code jti} been revoked?
     *
     * <p>Counterpart to {@link #findForValidation(String)} for <b>JWT mode</b>
     * ({@code plaintext.mcp.validation=JWT}, i.e. app/guild/schuetu). There is no
     * token hash to look up there — the filter only knows the claims — and that is why a
     * revocation was ineffective there for up to a year.
     *
     * <p><b>The difference to {@code findForValidation} lies in how a non-match is read</b>, and
     * that is the entire point: there, "no row" means <i>revoked or never issued</i>;
     * here it means <i>not revoked</i>. Only this way do the JWT-only tokens stay untouched that
     * have no row in {@code api_token} at all (time-tracking clock, Juriwagen, {@code minten}) —
     * exactly those that a switch to {@code validation=DATABASE} would lock out (card 305).
     *
     * @param jti {@code jti} claim of the token to be checked
     * @return {@code true} only for a row that was found and marked {@code invalidated}
     */
    boolean isJtiRevoked(String jti);

    /**
     * Advances the usage statistics: {@code last_used_at = now},
     * {@code use_count = use_count + 1}, {@code updated_at = now}.
     *
     * <p><b>Best effort</b> — if the write fails, the access decision already taken
     * remains valid. The audit columns {@code last_modified_by} /
     * {@code last_modified_date} are deliberately left untouched: <i>using</i> a token is not a
     * business change to the record, and in the filter context the JPA auditor would enter
     * {@code "system"} and thereby overwrite the last real editor.
     *
     * @param id primary key from {@link #findForValidation(String)}
     */
    void markUsed(long id);

    /**
     * The fields of a token that are read for the access decision.
     *
     * @param id          primary key, for {@link #markUsed(long)}
     * @param deleted     {@code SuperModel.deleted}; the column is <b>nullable</b>, {@code NULL}
     *                    is read as {@code false} (never having been deleted)
     * @param invalidated soft invalidation, column {@code NOT NULL}
     * @param userEmail   mail address of the owner, feeds into the validation result
     */
    record TokenZustand(long id, boolean deleted, boolean invalidated, String userEmail) {
    }
}

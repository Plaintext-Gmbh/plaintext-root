/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.apitoken;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link ApiTokenRevocationLookup} via {@link JdbcTemplate} — open the connection, read, hand it
 * back immediately.
 *
 * <p>The class deliberately sits in this module and not in {@code plaintext-root-webapp} (where
 * {@code McpUserRolesImpl} lives): table, entity, repository and the only caller
 * ({@link ApiTokenService}) all belong to {@code plaintext-admin-apitoken}. With
 * {@code McpUserRoles} the table {@code my_user_entity} was in a different module than the
 * filter — that was the only reason the split via an interface module was needed there.
 *
 * <p><b>Functionally identical to the previous JPA path</b> ({@code apiTokenRepository.findByTokenHash}
 * plus the two field checks in {@code validateVerifiedToken}); pinned down by the contract test
 * {@code ApiTokenRevocationVertragIT}, which puts both paths against the same data set.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Component
public class ApiTokenRevocationLookupJdbc implements ApiTokenRevocationLookup {

    private final JdbcTemplate jdbc;

    public ApiTokenRevocationLookupJdbc(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<TokenZustand> findForValidation(String tokenHash) {
        if (tokenHash == null || tokenHash.isEmpty()) {
            return Optional.empty();
        }
        // token_hash carries uk_api_token_hash (unique) — at most one row. queryForList instead of
        // queryForObject, so that "no match" is an empty Optional and not an exception.
        List<TokenZustand> treffer = jdbc.query(
                "SELECT id, deleted, invalidated, user_email FROM api_token WHERE token_hash = ?",
                // deleted is NULLABLE (V1775256894); getBoolean() returns false for NULL — that is
                // the intended reading "never having been deleted". On NULL the JPA path ran into a
                // NullPointerException (unboxing of Boolean), i.e. into a 500 instead of an
                // access decision; see the contract test.
                (rs, zeile) -> new TokenZustand(rs.getLong("id"), rs.getBoolean("deleted"),
                        rs.getBoolean("invalidated"), rs.getString("user_email")),
                tokenHash);
        return treffer.isEmpty() ? Optional.empty() : Optional.of(treffer.get(0));
    }

    @Override
    public boolean isJtiRevoked(String jti) {
        if (jti == null || jti.isEmpty()) {
            return false;
        }
        // Card 664: the query asks directly for the state in question instead of loading a row
        // and evaluating it here — the call sits in the auth path of every MCP request.
        // invalidated is NOT NULL (V1775256894), so the WHERE condition suffices; deleted is
        // deliberately NOT checked: invalidateToken() sets both flags, and a record that was only
        // tidied away (deleted) and not revoked is not a revocation.
        Integer treffer = jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_token WHERE jti = ? AND invalidated = TRUE",
                Integer.class, jti);
        return treffer != null && treffer > 0;
    }

    @Override
    public void markUsed(long id) {
        Timestamp jetzt = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("UPDATE api_token SET last_used_at = ?, use_count = use_count + 1, updated_at = ?"
                + " WHERE id = ?", jetzt, jetzt, id);
    }
}

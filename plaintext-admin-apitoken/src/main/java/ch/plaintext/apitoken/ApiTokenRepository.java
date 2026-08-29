/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for API tokens.
 * Supports multiple tokens per user.
 * Lookup is done by SHA-256 hash of the JWT token.
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@Repository
public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    /**
     * Find token by SHA-256 hash of the JWT token string.
     */
    Optional<ApiToken> findByTokenHash(String tokenHash);

    /**
     * Find all active (non-deleted) tokens for a user in a mandat.
     */
    List<ApiToken> findByUserIdAndMandatAndDeletedOrderByCreatedAtDesc(Long userId, String mandat, Boolean deleted);

    /**
     * Find a specific token by user, mandat, and name (non-deleted).
     */
    Optional<ApiToken> findByUserIdAndMandatAndTokenNameAndDeleted(Long userId, String mandat, String tokenName, Boolean deleted);

    /**
     * Count active tokens for a user (for limit checking).
     */
    long countByUserIdAndMandatAndDeleted(Long userId, String mandat, Boolean deleted);

    /**
     * Find all tokens in a mandat (for admin view).
     */
    List<ApiToken> findByMandatAndDeletedOrderByCreatedAtDesc(String mandat, Boolean deleted);

    /**
     * Find all tokens across all mandats (for root view).
     */
    List<ApiToken> findByDeletedOrderByCreatedAtDesc(Boolean deleted);

    /**
     * Find first active token for user (backwards compatibility).
     */
    default Optional<ApiToken> findFirstActiveByUserIdAndMandat(Long userId, String mandat) {
        List<ApiToken> tokens = findByUserIdAndMandatAndDeletedOrderByCreatedAtDesc(userId, mandat, false);
        return tokens.isEmpty() ? Optional.empty() : Optional.of(tokens.get(0));
    }
}

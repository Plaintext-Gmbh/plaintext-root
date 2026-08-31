/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.framework.SuperModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Entity for JWT API access tokens.
 * Stores only the SHA-256 hash of the JWT token (the token itself is never persisted).
 * The actual JWT is returned once at creation time and cannot be recovered.
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "api_token")
public class ApiToken extends SuperModel {

    /**
     * SHA-256 hash of the JWT token string (hex-encoded, 64 chars).
     * The actual JWT is never stored - only its hash for lookup/revocation.
     */
    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    /**
     * {@code jti} claim (JWT ID) of the issued token — the bridge between an incoming
     * Bearer token and its row here, without having to know the token itself (card 664).
     *
     * <p><b>{@code null} means "unknown", not "revoked".</b> Rows from the time before card
     * 664 cannot learn their jti after the fact; revoking them stays ineffective until the
     * token expires. That very distinction also leaves the JWT-only tokens
     * (time-tracking clock, Juriwagen, minten) untouched — they have no row here at all.</p>
     */
    @Column(name = "jti", length = 64)
    private String jti;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 100)
    private String description;

    /**
     * User-defined name for this token (e.g., "OpenClaw", "Backup Script").
     */
    @Column(length = 100)
    private String tokenName;

    /**
     * Email of the user who owns this token (included in JWT claims).
     */
    @Column(length = 255)
    private String userEmail;

    /**
     * Soft-invalidation flag (separate from SuperModel.deleted).
     * An invalidated token is no longer valid but remains in the database for auditing.
     */
    @Column(nullable = false)
    private boolean invalidated = false;

    /**
     * Token expiration timestamp.
     * After this time, the token is no longer valid.
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "use_count", nullable = false)
    private long useCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Check if token is expired.
     */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }
}

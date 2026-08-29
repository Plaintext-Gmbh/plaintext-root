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
     * {@code jti}-Claim (JWT-ID) des ausgestellten Tokens — die Brücke zwischen einem eingehenden
     * Bearer-Token und seiner Zeile hier, ohne den Token selbst zu kennen (Karte 664).
     *
     * <p><b>{@code null} heisst „unbekannt", nicht „widerrufen".</b> Zeilen aus der Zeit vor Karte
     * 664 können ihren jti nicht nachträglich erfahren; ihr Widerruf bleibt bis zum Ablauf des
     * Tokens wirkungslos. Genau diese Unterscheidung hält zugleich die JWT-only-Tokens
     * (Zeiterfassungs-Uhr, Juriwagen, minten) unberührt — sie haben hier gar keine Zeile.</p>
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

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.entity;

import ch.plaintext.anforderungen.security.ApiTokenHasher;
import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;

/**
 * API settings for Claude automation - per mandat
 * Each mandat has its own settings instance
 */
@Entity
@Table(name = "anforderung_api_settings", indexes = {
    @Index(name = "idx_api_settings_mandat", columnList = "mandat", unique = true)
})
@Data
public class AnforderungApiSettings implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mandat", nullable = false, length = 100, unique = true)
    private String mandat;

    @Column(name = "api_token", length = 500)
    private String apiToken;

    /**
     * SHA-256 hash (hex, 64 characters) of the API token — the server-side basis for
     * comparison (constant-time, see {@link ApiTokenHasher}). Derived automatically
     * from {@link #apiToken} on persist ({@link #syncApiTokenHash()}); for legacy rows
     * the lazy migration in {@code ClaudeAutomationService} backfills the hash on the
     * first successful cleartext match.
     */
    @Column(name = "api_token_hash", length = 64)
    private String apiTokenHash;

    @Column(name = "claude_automation_enabled")
    private Boolean claudeAutomationEnabled;

    /**
     * Keeps the hash consistent with the cleartext token on save: transitional phase — the
     * cleartext is (still) the source of truth (the settings UI shows/sets it). If the
     * token is cleared, the hash is cleared as well, otherwise the old token would stay valid.
     * Follow-up: drop the cleartext column, show the token only once.
     */
    @PrePersist
    @PreUpdate
    void syncApiTokenHash() {
        if (apiToken == null || apiToken.isBlank()) {
            apiTokenHash = null;
        } else {
            apiTokenHash = ApiTokenHasher.sha256Hex(apiToken);
        }
    }
}

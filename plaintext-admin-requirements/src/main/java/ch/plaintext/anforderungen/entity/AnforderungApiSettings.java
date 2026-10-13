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
     * Keeps the hash consistent with a legacy cleartext token on save.
     *
     * <p>Card 1449 S-12: the settings UI no longer writes cleartext — it stores only the hash and
     * shows a new token once. A row WITHOUT cleartext therefore keeps its hash (before, an empty
     * cleartext wiped the hash). {@code V1791865750} empties the legacy cleartext of every row
     * that has a hash (Daniel's OK, 13.10.2026); a row without hash keeps it until its token is
     * regenerated, otherwise that token would silently stop working.</p>
     */
    @PrePersist
    @PreUpdate
    void syncApiTokenHash() {
        if (apiToken != null && !apiToken.isBlank()) {
            apiTokenHash = ApiTokenHasher.sha256Hex(apiToken);
        }
    }
}

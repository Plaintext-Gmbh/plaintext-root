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
     * SHA-256-Hash (hex, 64 Zeichen) des API-Tokens — die serverseitige Vergleichsbasis
     * (konstantzeitig, siehe {@link ApiTokenHasher}). Wird beim Persistieren automatisch
     * aus {@link #apiToken} abgeleitet ({@link #syncApiTokenHash()}); für Alt-Zeilen zieht
     * die Lazy-Migration in {@code ClaudeAutomationService} den Hash beim ersten
     * erfolgreichen Klartext-Match nach.
     */
    @Column(name = "api_token_hash", length = 64)
    private String apiTokenHash;

    @Column(name = "claude_automation_enabled")
    private Boolean claudeAutomationEnabled;

    /**
     * Hält den Hash beim Speichern konsistent zum Klartext-Token: Übergangsphase — der
     * Klartext ist (noch) die Quelle der Wahrheit (Settings-UI zeigt/setzt ihn). Wird der
     * Token geleert, wird auch der Hash geleert, sonst bliebe der alte Token gültig.
     * Follow-up: Klartext-Spalte entfernen, Token nur noch einmalig anzeigen.
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

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.entity;

import ch.plaintext.anforderungen.security.ApiTokenHasher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnforderungApiSettingsTest {

    @Test
    void defaultConstructorCreatesEmptyInstance() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        assertThat(settings.getId()).isNull();
        assertThat(settings.getMandat()).isNull();
        assertThat(settings.getApiToken()).isNull();
        assertThat(settings.getClaudeAutomationEnabled()).isNull();
    }

    @Test
    void settersAndGettersWork() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setId(1L);
        settings.setMandat("mandatA");
        settings.setApiToken("token-123");
        settings.setClaudeAutomationEnabled(true);

        assertThat(settings.getId()).isEqualTo(1L);
        assertThat(settings.getMandat()).isEqualTo("mandatA");
        assertThat(settings.getApiToken()).isEqualTo("token-123");
        assertThat(settings.getClaudeAutomationEnabled()).isTrue();
    }

    // --- syncApiTokenHash (JPA lifecycle hook, @PrePersist/@PreUpdate) ---

    @Test
    void syncApiTokenHashDerivesHashFromPlaintextToken() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token-123");

        settings.syncApiTokenHash();

        assertThat(settings.getApiTokenHash())
                .isEqualTo(ApiTokenHasher.sha256Hex("token-123"))
                .hasSize(64);
    }

    @Test
    void syncApiTokenHashUpdatesStaleHashWhenTokenChanges() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("old-token");
        settings.syncApiTokenHash();
        String oldHash = settings.getApiTokenHash();

        settings.setApiToken("new-token");
        settings.syncApiTokenHash();

        assertThat(settings.getApiTokenHash())
                .isNotEqualTo(oldHash)
                .isEqualTo(ApiTokenHasher.sha256Hex("new-token"));
    }

    /**
     * Card 1449 S-12: the settings UI stores only the hash. A row without cleartext must keep its
     * hash on save — before, the empty cleartext wiped it and the token stopped working.
     */
    @Test
    void syncApiTokenHashKeepsHashOfHashOnlyRow() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiTokenHash(ApiTokenHasher.sha256Hex("token-123"));
        settings.setApiToken(null);
        settings.syncApiTokenHash();
        assertThat(settings.getApiTokenHash()).isEqualTo(ApiTokenHasher.sha256Hex("token-123"));
        assertThat(settings.getApiToken()).isNull();
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.repository;

import ch.plaintext.anforderungen.entity.AnforderungApiSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AnforderungApiSettingsRepository extends JpaRepository<AnforderungApiSettings, Long> {

    Optional<AnforderungApiSettings> findByMandat(String mandat);

    /**
     * Legacy cleartext lookup — only for the transitional phase / lazy migration
     * (legacy rows without {@code api_token_hash}). New validations go through
     * {@link #findByApiTokenHash(String)}.
     */
    Optional<AnforderungApiSettings> findByApiToken(String apiToken);

    /** Lookup by the SHA-256 hash (hex) of the token — the canonical way. */
    Optional<AnforderungApiSettings> findByApiTokenHash(String apiTokenHash);
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.selfservice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RegistrationTokenRepository extends JpaRepository<RegistrationToken, Long> {

    // SECURITY (card 307, K2.3): lookup only via the SHA-256 hash; the clear-text token exists only
    // in the e-mail link, never in the DB.
    Optional<RegistrationToken> findByTokenHash(String tokenHash);

    /**
     * Redeems a token atomically (conditional UPDATE) — prevents TOCTOU races.
     *
     * @return number of updated rows (1 = success, 0 = invalid/expired/used)
     */
    @Modifying
    @Query("UPDATE RegistrationToken t SET t.consumedAt = :now " +
           "WHERE t.tokenHash = :hash AND t.consumedAt IS NULL AND t.expiresAt > :now")
    int consumeToken(@Param("hash") String hash, @Param("now") Instant now);
}

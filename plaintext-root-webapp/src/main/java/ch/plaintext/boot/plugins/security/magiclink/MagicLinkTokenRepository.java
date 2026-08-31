/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.magiclink;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MagicLinkTokenRepository extends JpaRepository<MagicLinkToken, Long> {

    Optional<MagicLinkToken> findByTokenHash(String tokenHash);

    /**
     * Redeems a token atomically: sets {@code consumedAt} exactly when the token exists,
     * has not yet been redeemed and has not expired. The conditional UPDATE prevents TOCTOU races
     * (double click/replay): with two parallel calls exactly one wins.
     *
     * @return number of updated rows (1 = successfully redeemed, 0 = invalid/expired/already used)
     */
    @Modifying
    @Query("UPDATE MagicLinkToken t SET t.consumedAt = :now " +
           "WHERE t.tokenHash = :hash AND t.consumedAt IS NULL AND t.expiresAt > :now")
    int consumeToken(@Param("hash") String hash, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM MagicLinkToken t WHERE t.expiresAt < :cutoff")
    void deleteExpiredBefore(Instant cutoff);
}

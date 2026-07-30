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
     * Loest einen Token atomar ein: setzt {@code consumedAt} genau dann, wenn der Token existiert,
     * noch nicht eingeloest und nicht abgelaufen ist. Das bedingte UPDATE verhindert TOCTOU-Races
     * (Doppelklick/Replay): bei zwei parallelen Aufrufen gewinnt genau einer.
     *
     * @return Anzahl aktualisierter Zeilen (1 = erfolgreich eingeloest, 0 = ungueltig/abgelaufen/bereits verwendet)
     */
    @Modifying
    @Query("UPDATE MagicLinkToken t SET t.consumedAt = :now " +
           "WHERE t.tokenHash = :hash AND t.consumedAt IS NULL AND t.expiresAt > :now")
    int consumeToken(@Param("hash") String hash, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM MagicLinkToken t WHERE t.expiresAt < :cutoff")
    void deleteExpiredBefore(Instant cutoff);
}

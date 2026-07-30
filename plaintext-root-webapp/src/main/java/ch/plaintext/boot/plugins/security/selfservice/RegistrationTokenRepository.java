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

    // SECURITY (Karte 307, K2.3): Lookup nur ueber den SHA-256-Hash; der Klartext-Token existiert nur
    // im E-Mail-Link, nie in der DB.
    Optional<RegistrationToken> findByTokenHash(String tokenHash);

    /**
     * Loest einen Token atomar ein (bedingtes UPDATE) — verhindert TOCTOU-Races.
     *
     * @return Anzahl aktualisierter Zeilen (1 = erfolgreich, 0 = ungueltig/abgelaufen/verwendet)
     */
    @Modifying
    @Query("UPDATE RegistrationToken t SET t.consumedAt = :now " +
           "WHERE t.tokenHash = :hash AND t.consumedAt IS NULL AND t.expiresAt > :now")
    int consumeToken(@Param("hash") String hash, @Param("now") Instant now);
}

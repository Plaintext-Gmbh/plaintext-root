/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository für CronConfigEntity.
 */
@Repository
public interface CronConfigRepository extends JpaRepository<CronConfigEntity, Long> {

    Optional<CronConfigEntity> findByCronNameAndMandat(String cronName, String mandat);

    List<CronConfigEntity> findByMandat(String mandat);

    List<CronConfigEntity> findByCronName(String cronName);

    void deleteByCronNameAndMandat(String cronName, String mandat);

    /**
     * Findet die Bestandszeile desselben Jobs unter einem Proxy-Namen (Praefix {@code <name>$$}).
     * Siehe {@link CronConfigStore#findLegacyProxyRow(String, String)}. Aeltester Treffer zuerst:
     * bei mehreren Proxy-Generationen ist die urspruengliche Zeile die mit den Einstellungen.
     */
    Optional<CronConfigEntity> findFirstByCronNameStartingWithAndMandatOrderByIdAsc(String prefix, String mandat);

}

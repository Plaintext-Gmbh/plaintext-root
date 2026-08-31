/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for CronConfigEntity.
 */
@Repository
public interface CronConfigRepository extends JpaRepository<CronConfigEntity, Long> {

    Optional<CronConfigEntity> findByCronNameAndMandat(String cronName, String mandat);

    List<CronConfigEntity> findByMandat(String mandat);

    List<CronConfigEntity> findByCronName(String cronName);

    void deleteByCronNameAndMandat(String cronName, String mandat);

    /**
     * Finds the existing row of the same job stored under a proxy name (prefix {@code <name>$$}).
     * See {@link CronConfigStore#findLegacyProxyRow(String, String)}. Oldest match first:
     * with several proxy generations the original row is the one that carries the settings.
     */
    Optional<CronConfigEntity> findFirstByCronNameStartingWithAndMandatOrderByIdAsc(String prefix, String mandat);

}

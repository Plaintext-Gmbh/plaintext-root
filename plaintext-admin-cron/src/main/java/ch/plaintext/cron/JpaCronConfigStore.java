/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;

/**
 * The default {@link CronConfigStore}: cron configuration in the {@code cron_config} table, via
 * {@link CronConfigRepository}.
 *
 * @since 1.480.0
 */
@RequiredArgsConstructor
public class JpaCronConfigStore implements CronConfigStore {

    private final CronConfigRepository repository;

    @Override
    public Optional<CronConfigEntity> findByCronNameAndMandat(String cronName, String mandat) {
        return repository.findByCronNameAndMandat(cronName, mandat);
    }

    @Override
    public CronConfigEntity save(CronConfigEntity entity) {
        return repository.save(entity);
    }

    @Override
    public List<CronConfigEntity> findAll() {
        return repository.findAll();
    }

    /**
     * Same answer as the interface default, but as a query instead of a full table scan.
     */
    @Override
    public Optional<CronConfigEntity> findLegacyProxyRow(String cronName, String mandat) {
        return repository.findFirstByCronNameStartingWithAndMandatOrderByIdAsc(cronName + "$$", mandat);
    }
}

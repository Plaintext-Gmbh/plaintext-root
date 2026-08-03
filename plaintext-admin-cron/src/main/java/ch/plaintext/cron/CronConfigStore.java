/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import java.util.List;
import java.util.Optional;

/**
 * Where cron configuration lives.
 * <p>
 * The module ships {@link JpaCronConfigStore}, which keeps the configuration in the
 * {@code cron_config} table. Applications that hold their configuration elsewhere — a wiki page, a
 * config service, a git repository — provide their own bean of this type and the JPA default steps
 * aside.
 * <p>
 * Implementations must be safe to call before the scheduler starts, and should degrade to an empty
 * result rather than throwing when their backing store is unreachable: a cron configuration that
 * cannot be read must not prevent the application from starting.
 *
 * @since 1.480.0
 */
public interface CronConfigStore {

    /**
     * @param cronName the job's name
     * @param mandat   the mandate the row belongs to, or {@code global}
     * @return the stored configuration, or empty when the job is not yet known
     */
    Optional<CronConfigEntity> findByCronNameAndMandat(String cronName, String mandat);

    /**
     * Stores a configuration, inserting or updating as needed.
     *
     * @param entity the configuration to persist
     * @return the persisted configuration, which may be a different instance
     */
    CronConfigEntity save(CronConfigEntity entity);

    /**
     * @return every stored configuration across all mandates
     */
    List<CronConfigEntity> findAll();
}

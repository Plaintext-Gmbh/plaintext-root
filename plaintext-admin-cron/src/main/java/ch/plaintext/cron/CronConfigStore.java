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

    /**
     * Finds a row that belongs to the same job under an <em>older</em> name.
     * <p>
     * Job names have changed before: until 03.08.2026 a job was stored under its CGLIB proxy name
     * ({@code KontaktEmailAvisTrigger$$SpringCGLIB$$0}), afterwards under the plain class name. A
     * lookup by the new name finds nothing, a fresh row is created with the code defaults — and
     * every setting made by hand silently stops applying while the old row stays behind as a
     * leftover. That happened to 99 rows across 14 jobs (card 574).
     * <p>
     * The marker is the {@code $$} that separates a class name from any proxy suffix; it cannot
     * appear in a Java class name, so a row whose name starts with {@code <name>$$} is the same job
     * under a generated alias.
     *
     * @param cronName the job's current name, without any suffix
     * @param mandat   the mandate the row belongs to, or {@code global}
     * @return the leftover row, or empty when there is none
     * @since 1.517.0
     */
    default Optional<CronConfigEntity> findLegacyProxyRow(String cronName, String mandat) {
        // Deliberately a default method: implementations outside this module (a wiki page, a config
        // service) keep working without change, and they inherit a correct — if unindexed — answer.
        String prefix = cronName + "$$";
        return findAll().stream()
                .filter(e -> mandat != null && mandat.equals(e.getMandat()))
                .filter(e -> e.getCronName() != null && e.getCronName().startsWith(prefix))
                .findFirst();
    }
}

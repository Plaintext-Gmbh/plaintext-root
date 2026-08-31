/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.persistence;

import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for MandateMenuConfig entities.
 *
 * @author plaintext.ch
 * @since 1.39.0
 */
@Repository
public interface MandateMenuConfigRepository extends JpaRepository<MandateMenuConfig, Long> {

    /**
     * Find configuration by mandate name.
     *
     * @param mandateName the mandate name
     * @return the configuration if found
     */
    Optional<MandateMenuConfig> findByMandateName(String mandateName);

    /**
     * Check if configuration exists for a mandate.
     *
     * @param mandateName the mandate name
     * @return true if exists
     */
    boolean existsByMandateName(String mandateName);

    /**
     * Like {@link #findByMandateName(String)}, but ignoring case.
     *
     * <p>Tenant names are <b>not</b> case-consistent in the existing data — in {@code user_session}
     * {@code BUTSCHER} was stored in upper case, while the same tenant is written in lower case
     * everywhere else. Anyone who wants to reliably hit a tenant configuration has to search
     * case-insensitively.</p>
     *
     * @param mandateName the tenant name in any spelling
     * @return the configuration, if present
     * @since 1.608.0
     */
    Optional<MandateMenuConfig> findByMandateNameIgnoreCase(String mandateName);
}

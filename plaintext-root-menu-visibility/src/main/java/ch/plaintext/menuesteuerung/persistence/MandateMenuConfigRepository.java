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
     * Wie {@link #findByMandateName(String)}, aber ohne Ruecksicht auf Gross-/Kleinschreibung.
     *
     * <p>Mandantennamen sind im Bestand <b>nicht</b> case-konsistent — in {@code user_session}
     * stand {@code BUTSCHER} gross, waehrend derselbe Mandant ueberall sonst klein geschrieben ist.
     * Wer eine Mandanten-Konfiguration sicher treffen will, muss case-insensitiv suchen.</p>
     *
     * @param mandateName der Mandantenname in beliebiger Schreibweise
     * @return die Konfiguration, wenn vorhanden
     * @since 1.608.0
     */
    Optional<MandateMenuConfig> findByMandateNameIgnoreCase(String mandateName);
}

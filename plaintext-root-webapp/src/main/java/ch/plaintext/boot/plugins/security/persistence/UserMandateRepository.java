/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.persistence;

import ch.plaintext.boot.plugins.security.model.UserMandate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository für die zusätzlichen Mandanten-Zuordnungen eines Benutzers
 * ({@link UserMandate}).
 *
 * @author mad
 * @since 2026
 */
@Repository
public interface UserMandateRepository extends JpaRepository<UserMandate, Long> {

    /** Alle (auch inaktive) Zuordnungen eines Benutzers. */
    List<UserMandate> findByUsername(String username);

    /** Aktive Zuordnungen eines Benutzers. */
    List<UserMandate> findByUsernameAndActiveTrue(String username);

    /** Aktive Zuordnungen für einen Mandanten (alle Benutzer mit diesem Zusatz-Mandant). */
    List<UserMandate> findByMandatAndActiveTrue(String mandat);

    /**
     * Alle Zuordnungen (auch inaktive) zu einem Mandanten, ohne Rücksicht auf
     * Groß-/Kleinschreibung.
     *
     * <p>Mandantennamen sind im Bestand <b>nicht</b> case-konsistent — in {@code user_session}
     * stand {@code BUTSCHER} groß, während derselbe Mandant überall sonst klein geschrieben ist.
     * Wer prüfen will, ob einem Mandanten noch Benutzer zugeordnet sind, muss deshalb
     * case-insensitiv vergleichen; sonst meldet die Prüfung „keine Benutzer betroffen", obwohl
     * welche zugeordnet sind.</p>
     *
     * @param mandat der Mandantenname in beliebiger Schreibweise
     * @return alle Zuordnungen zu diesem Mandanten
     * @since 1.608.0
     */
    List<UserMandate> findByMandatIgnoreCase(String mandat);

    /** Entfernt alle Zuordnungen eines Benutzers (für vollständiges Neusetzen). */
    void deleteByUsername(String username);
}

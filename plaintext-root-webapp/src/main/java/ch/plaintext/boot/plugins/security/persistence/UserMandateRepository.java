/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.persistence;

import ch.plaintext.boot.plugins.security.model.UserMandate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for the additional tenant assignments of a user
 * ({@link UserMandate}).
 *
 * @author mad
 * @since 2026
 */
@Repository
public interface UserMandateRepository extends JpaRepository<UserMandate, Long> {

    /** All (including inactive) assignments of a user. */
    List<UserMandate> findByUsername(String username);

    /** Active assignments of a user. */
    List<UserMandate> findByUsernameAndActiveTrue(String username);

    /** Active assignments for a tenant (all users with this additional tenant). */
    List<UserMandate> findByMandatAndActiveTrue(String mandat);

    /**
     * All assignments (including inactive ones) for a tenant, regardless of
     * upper/lower case.
     *
     * <p>Tenant names are <b>not</b> case-consistent in the existing data — in {@code user_session}
     * {@code BUTSCHER} was stored in upper case, while the same tenant is written in lower case
     * everywhere else. Whoever wants to check whether users are still assigned to a tenant must
     * therefore compare case-insensitively; otherwise the check reports "no users affected" although
     * some are assigned.</p>
     *
     * @param mandat the tenant name in any spelling
     * @return all assignments for this tenant
     * @since 1.608.0
     */
    List<UserMandate> findByMandatIgnoreCase(String mandat);

    /** Removes all assignments of a user (for a complete re-set). */
    void deleteByUsername(String username);
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.impersonation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImpersonationAuditRepository extends JpaRepository<ImpersonationAudit, Long> {

    List<ImpersonationAudit> findAllByOrderByStartedAtDesc();

    /** The most recently started impersonation for this admin that has not been ended yet (if any). */
    Optional<ImpersonationAudit> findFirstByAdminUserIdAndEndedAtIsNullOrderByStartedAtDesc(Long adminUserId);
}

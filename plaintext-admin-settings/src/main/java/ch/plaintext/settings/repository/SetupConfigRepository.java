/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.repository;

import ch.plaintext.settings.entity.SetupConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SetupConfigRepository extends JpaRepository<SetupConfig, Long> {

    Optional<SetupConfig> findByMandat(String mandat);

    Optional<SetupConfig> findFirstByOidcAutoRedirectEnabledTrue();

    Optional<SetupConfig> findFirstByPasswordManagementEnabledFalse();

    Optional<SetupConfig> findFirstBySelfRegistrationEnabledTrue();

    Optional<SetupConfig> findFirstByPasswordResetLinkEnabledTrue();

    Optional<SetupConfig> findFirstByMagicLinkEnabledTrue();

    Optional<SetupConfig> findFirstByTotpEnabledTrue();

    /** First configuration with a GLOBAL system mail account set (system-wide resolution for auth mails). */
    Optional<SetupConfig> findFirstBySystemMailAccountIdNotNull();
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.framework.PlaintextRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SecretBackendConfigRepository extends JpaRepository<SecretBackendConfig, Long>, PlaintextRepository<SecretBackendConfig> {

    Optional<SecretBackendConfig> findFirstByMandatAndAktivAndDeleted(String mandat, boolean aktiv, boolean deleted);
}

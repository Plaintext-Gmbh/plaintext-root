/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.framework.PlaintextRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SecretEntryRepository extends JpaRepository<SecretEntry, Long>, PlaintextRepository<SecretEntry> {

    List<SecretEntry> findByMandatAndDeletedOrderByNameAsc(String mandat, boolean deleted);

    Optional<SecretEntry> findByMandatAndName(String mandat, String name);
}

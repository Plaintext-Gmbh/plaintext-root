/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe.link.repository;

import ch.plaintext.freigabe.link.entity.FreigabeLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Freigabe-Links (Karte 1476). */
public interface FreigabeLinkRepository extends JpaRepository<FreigabeLink, Long> {

    Optional<FreigabeLink> findByTokenHashAndDeletedFalse(String tokenHash);

    List<FreigabeLink> findByMandatAndDeletedFalseOrderByIdDesc(String mandat);

    Optional<FreigabeLink> findByIdAndMandatAndDeletedFalse(Long id, String mandat);
}

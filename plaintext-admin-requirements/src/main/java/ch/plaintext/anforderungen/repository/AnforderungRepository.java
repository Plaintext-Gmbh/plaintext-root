/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.repository;

import ch.plaintext.anforderungen.entity.Anforderung;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnforderungRepository extends JpaRepository<Anforderung, Long> {

    List<Anforderung> findByMandat(String mandat);

    List<Anforderung> findByMandatOrderByCreatedDateDesc(String mandat);

    List<Anforderung> findByMandatAndStatus(String mandat, String status);

    List<Anforderung> findByMandatAndPriority(String mandat, String priority);

    List<Anforderung> findByErsteller(String username);

    long countByMandatAndStatus(String mandat, String status);
}

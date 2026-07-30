/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.repository;

import ch.plaintext.anforderungen.entity.Howto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HowtoRepository extends JpaRepository<Howto, Long> {

    List<Howto> findByActiveTrue();

    List<Howto> findByMandatAndActiveTrue(String mandat);

    Optional<Howto> findByName(String name);

    Optional<Howto> findByMandatAndName(String mandat, String name);

    List<Howto> findByMandat(String mandat);
}

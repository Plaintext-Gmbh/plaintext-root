/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.repository;

import ch.plaintext.sidecars.entity.Sidecar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Karte 1400. */
public interface SidecarRepository extends JpaRepository<Sidecar, Long> {

    List<Sidecar> findByDeletedFalseOrderByNameAsc();

    Optional<Sidecar> findFirstByNameAndDeletedFalse(String name);
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.repository;

import ch.plaintext.anforderungen.entity.ConstraintTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConstraintTemplateRepository extends JpaRepository<ConstraintTemplate, Long> {

    Optional<ConstraintTemplate> findByTitel(String titel);

    Optional<ConstraintTemplate> findByMandatAndTitel(String mandat, String titel);

    boolean existsByTitel(String titel);

    boolean existsByMandatAndTitel(String mandat, String titel);

    List<ConstraintTemplate> findByMandat(String mandat);
}

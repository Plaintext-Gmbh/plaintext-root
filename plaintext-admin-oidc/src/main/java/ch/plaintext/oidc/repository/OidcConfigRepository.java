/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.oidc.repository;

import ch.plaintext.oidc.entity.OidcConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OidcConfigRepository extends JpaRepository<OidcConfig, Long> {

    List<OidcConfig> findByEnabledTrue();

    Optional<OidcConfig> findFirstByEnabledTrue();
}

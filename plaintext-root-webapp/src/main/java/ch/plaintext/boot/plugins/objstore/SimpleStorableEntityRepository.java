/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.objstore;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SimpleStorableEntityRepository extends JpaRepository<SimpleStorableEntity, Long> {

    SimpleStorableEntity findByUniqueId(String uniqueId);

}
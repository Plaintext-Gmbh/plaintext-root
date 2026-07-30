/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import ch.plaintext.framework.PlaintextRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModuleConfigRepository extends JpaRepository<ModuleConfig, Long>,
        PlaintextRepository<ModuleConfig> {

    Optional<ModuleConfig> findByModuleId(String moduleId);
}

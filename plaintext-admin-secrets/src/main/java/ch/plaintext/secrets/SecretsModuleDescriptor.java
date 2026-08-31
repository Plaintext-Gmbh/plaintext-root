/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.modules.ModuleDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Registers the secrets module with the central module management (Task #016). */
@Component
public class SecretsModuleDescriptor implements ModuleDescriptor {
    @Override
    public String moduleId() {
        return "secrets";
    }

    @Override
    public String displayName() {
        return "Secrets";
    }

    @Override
    public List<Class<?>> entities() {
        return List.of(SecretEntry.class, SecretBackendConfig.class);
    }
}

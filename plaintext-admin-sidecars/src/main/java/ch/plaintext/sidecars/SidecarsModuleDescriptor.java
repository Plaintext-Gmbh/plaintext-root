/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars;

import ch.plaintext.modules.ModuleDescriptor;
import ch.plaintext.sidecars.entity.Sidecar;
import org.springframework.stereotype.Component;

import java.util.List;

/** Karte 1400: Sidecar-Registry. */
@Component
public class SidecarsModuleDescriptor implements ModuleDescriptor {

    @Override
    public String moduleId() {
        return "sidecars";
    }

    @Override
    public String displayName() {
        return "Sidecars";
    }

    @Override
    public List<Class<?>> entities() {
        return List.of(Sidecar.class);
    }
}

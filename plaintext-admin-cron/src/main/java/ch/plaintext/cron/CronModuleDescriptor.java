/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.modules.ModuleDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Meldet das Cron-Modul an die zentrale Modul-Verwaltung (Task #016). */
@Component
public class CronModuleDescriptor implements ModuleDescriptor {
    @Override
    public String moduleId() {
        return "cron";
    }

    @Override
    public String displayName() {
        return "Cron";
    }

    @Override
    public List<Class<?>> entities() {
        return List.of(CronConfigEntity.class);
    }
}

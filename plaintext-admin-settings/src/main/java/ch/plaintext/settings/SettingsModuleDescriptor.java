/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings;

import ch.plaintext.modules.ModuleDescriptor;
import ch.plaintext.settings.entity.BrandingLogo;
import ch.plaintext.settings.entity.Setting;
import ch.plaintext.settings.entity.SetupConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/** Meldet das Settings-Modul an die zentrale Modul-Verwaltung (Task #016). */
@Component
public class SettingsModuleDescriptor implements ModuleDescriptor {
    @Override
    public String moduleId() {
        return "settings";
    }

    @Override
    public String displayName() {
        return "Settings";
    }

    @Override
    public List<Class<?>> entities() {
        return List.of(Setting.class, SetupConfig.class, BrandingLogo.class);
    }
}

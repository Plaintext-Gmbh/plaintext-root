/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n;

import ch.plaintext.i18n.entity.I18nTranslation;
import ch.plaintext.modules.ModuleDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Registers the translations module with the central module management (Task #016). */
@Component
public class I18nModuleDescriptor implements ModuleDescriptor {
    @Override
    public String moduleId() {
        return "i18n";
    }

    @Override
    public String displayName() {
        return "Übersetzungen";
    }

    @Override
    public List<Class<?>> entities() {
        return List.of(I18nTranslation.class);
    }
}

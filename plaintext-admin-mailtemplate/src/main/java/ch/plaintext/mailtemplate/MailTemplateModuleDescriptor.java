/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate;

import ch.plaintext.mailtemplate.entity.MailTemplate;
import ch.plaintext.modules.ModuleDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Meldet das Mailtext-Modul an die zentrale Modul-Verwaltung (analog i18n). */
@Component
public class MailTemplateModuleDescriptor implements ModuleDescriptor {
    @Override
    public String moduleId() {
        return "mailtemplate";
    }

    @Override
    public String displayName() {
        return "Mailtexte";
    }

    @Override
    public List<Class<?>> entities() {
        return List.of(MailTemplate.class);
    }
}

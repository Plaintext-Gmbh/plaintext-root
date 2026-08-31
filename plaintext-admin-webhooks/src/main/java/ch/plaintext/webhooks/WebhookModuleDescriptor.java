/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks;

import ch.plaintext.modules.ModuleDescriptor;
import ch.plaintext.webhooks.entity.WebhookDelivery;
import ch.plaintext.webhooks.entity.WebhookEndpoint;
import org.springframework.stereotype.Component;

import java.util.List;

/** Registers the webhook module with the central module management (analogous to mailtemplate/i18n). */
@Component
public class WebhookModuleDescriptor implements ModuleDescriptor {
    @Override
    public String moduleId() {
        return "webhooks";
    }

    @Override
    public String displayName() {
        return "Webhooks";
    }

    @Override
    public List<Class<?>> entities() {
        return List.of(WebhookEndpoint.class, WebhookDelivery.class);
    }
}

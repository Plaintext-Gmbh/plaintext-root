/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications;

import ch.plaintext.modules.ModuleDescriptor;
import ch.plaintext.notifications.entity.Notification;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NotificationModuleDescriptor implements ModuleDescriptor {
    @Override
    public String moduleId() {
        return "notifications";
    }

    @Override
    public String displayName() {
        return "Benachrichtigungen";
    }

    @Override
    public List<Class<?>> entities() {
        return List.of(Notification.class);
    }
}

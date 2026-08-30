/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.web;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.notifications.entity.Notification;
import ch.plaintext.notifications.service.NotificationServiceImpl;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;

/**
 * Lightweight Backing Bean for the bell in the header ({@code topbar.xhtml}, shared across all
 * apps with the root layout). The header checks {@code #{notificationTopbarBean != null}} — if this
 * bean does not exist (module not on the classpath of the respective app), the bell simply stays
 * invisible, exactly the established pattern of {@code i18nService}.
 */
@Component("notificationTopbarBean")
@Scope("request")
@Data
public class NotificationTopbarBean implements Serializable {

    @Autowired
    private transient NotificationServiceImpl notificationService;

    public long getUngelesenCount() {
        String user = PlaintextSecurityHolder.getUser();
        return user == null ? 0 : notificationService.countUngelesen(user);
    }

    public List<Notification> getLetzte() {
        String user = PlaintextSecurityHolder.getUser();
        return user == null ? List.of() : notificationService.getInbox(user, 8);
    }

    public void markiereGelesen(Long id) {
        notificationService.markiereGelesen(id, PlaintextSecurityHolder.getUser());
    }

    public void markiereAlleGelesen() {
        notificationService.markiereAlleGelesen(PlaintextSecurityHolder.getUser());
    }
}

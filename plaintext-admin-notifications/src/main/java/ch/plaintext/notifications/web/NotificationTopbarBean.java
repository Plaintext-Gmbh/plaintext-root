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
 * Leichtgewichtiges Backing Bean fuer die Glocke im Header ({@code topbar.xhtml}, geteilt ueber alle
 * Apps mit root-Layout). Der Header prueft {@code #{notificationTopbarBean != null}} — existiert diese
 * Bean nicht (Modul nicht auf dem Klassenpfad der jeweiligen App), bleibt die Glocke einfach unsichtbar,
 * exakt das etablierte Muster von {@code i18nService}.
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

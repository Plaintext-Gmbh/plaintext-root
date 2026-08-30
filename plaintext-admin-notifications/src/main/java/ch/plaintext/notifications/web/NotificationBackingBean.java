/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.web;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.notifications.entity.Notification;
import ch.plaintext.notifications.service.NotificationServiceImpl;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** Backing Bean of the full inbox page ({@code notifications.xhtml}). */
@Component("notificationBean")
@Scope("session")
@Data
@Slf4j
public class NotificationBackingBean implements Serializable {

    @Autowired
    private transient NotificationServiceImpl notificationService;

    private List<Notification> notifications;

    @PostConstruct
    public void onLoad() {
        refresh();
    }

    public void refresh() {
        notifications = notificationService.getInbox(PlaintextSecurityHolder.getUser(), 200);
    }

    public void markiereGelesen(Notification n) {
        notificationService.markiereGelesen(n.getId(), PlaintextSecurityHolder.getUser());
        refresh();
    }

    public void markiereAlleGelesen() {
        int n = notificationService.markiereAlleGelesen(PlaintextSecurityHolder.getUser());
        refresh();
        info("Erledigt", n + " Benachrichtigung(en) als gelesen markiert.");
    }

    /** Creates a test notification addressed to oneself — proof/verification of the mechanism. */
    public void sendeTestBenachrichtigung() {
        notificationService.notify(PlaintextSecurityHolder.getUser(), PlaintextSecurityHolder.getMandat(), "test",
                "Test-Benachrichtigung", "Dies ist eine Test-Benachrichtigung, manuell über die Admin-Seite ausgelöst.",
                Map.of(), null);
        refresh();
        info("Gesendet", "Test-Benachrichtigung erzeugt.");
    }

    private void info(String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, summary, detail));
    }
}

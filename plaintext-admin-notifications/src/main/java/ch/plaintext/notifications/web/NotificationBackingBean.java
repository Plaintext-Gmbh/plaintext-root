/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.notifications.entity.Notification;
import ch.plaintext.notifications.service.NotificationServiceImpl;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Backing Bean of the full inbox page ({@code notifications.xhtml}).
 *
 * <h2>Why field injection stays here (java:S6813, card 1273)</h2>
 *
 * <p>This bean is <b>session-scoped and serializable</b> — the one case in which constructor
 * injection is the wrong answer. On a deserialization <b>no constructor runs</b>: a service
 * field set only through the
 * constructor stays {@code null} forever, and being {@code final} nothing can set it afterwards,
 * not even the context. A {@code NotSerializableException} would thereby turn into a permanent
 * {@code NullPointerException} (cards 915/1246). Field injection ({@code @Autowired}, not
 * {@code final}) lets the context refill the field after a deserialization — that is the house
 * rule, and {@code PlaintextSessionBeanSerialisierbarTest} enforces it as a build-breaking
 * guard.</p>
 *
 * <p>{@code java:S6813} demands the exact opposite at these fields, so both cannot hold at once.
 * The guard wins: it protects against a defect that is silent and permanent, the rule protects a
 * style. The suppression sits on the class because every injected service of such a bean falls
 * under the house rule — card 1273 carries the measurement and the decision.</p>
 */
@Component("notificationBean")
@Scope("session")
@Data
@Slf4j
@SuppressWarnings("java:S6813") // begruendet im Klassenkommentar oben (Karte 1273) — nicht umbauen
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
        FacesMessages.info(summary, detail);
    }
}

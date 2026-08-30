/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.mailtemplate.IMailTemplateProvider;
import ch.plaintext.mailtemplate.IMailTemplateProvider.RenderedMail;
import ch.plaintext.notifications.NotificationService;
import ch.plaintext.notifications.entity.Notification;
import ch.plaintext.notifications.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final IMailTemplateProvider mailTemplateProvider;
    private final PlaintextSecurity security;

    @Override
    @Transactional
    public void notify(String empfaengerUsername, String mandat, String typ, String defaultTitel,
                        String defaultText, Map<String, String> platzhalter, String link) {
        if (empfaengerUsername == null || empfaengerUsername.isBlank()) {
            return;
        }
        RenderedMail rendered = mailTemplateProvider.render(mandat, "notif." + typ, defaultTitel, defaultText,
                platzhalter != null ? platzhalter : Map.of());
        Notification n = new Notification();
        n.setMandat(mandat);
        n.setEmpfaengerUsername(empfaengerUsername);
        n.setTyp(typ);
        n.setTitel(rendered.betreff());
        n.setText(rendered.body());
        n.setLink(link);
        notificationRepository.save(n);
    }

    // Sonar java:S2229 (card 891): notify() carries @Transactional, but was reached here by
    // SELF-INVOCATION — such a call bypasses the Spring proxy, and the annotation has no effect. The
    // notifications therefore ran without the promised transaction boundary, every save() on its own.
    // The boundary therefore belongs here: one event creates the notifications for all recipients
    // of the tenant, or none at all.
    @Override
    @Transactional
    public void notifyMandant(String mandat, String typ, String defaultTitel, String defaultText,
                               Map<String, String> platzhalter, String link) {
        for (String username : security.getUsersForMandat(mandat)) {
            notify(username, mandat, typ, defaultTitel, defaultText, platzhalter, link);
        }
    }

    /** Latest notifications of a user, newest first (for the inbox page and the topbar bell). */
    public List<Notification> getInbox(String username, int limit) {
        return notificationRepository.findByEmpfaengerUsernameAndDeletedFalseOrderByCreatedDateDesc(
                username, PageRequest.of(0, limit));
    }

    public long countUngelesen(String username) {
        return notificationRepository.countByEmpfaengerUsernameAndGelesenAmIsNullAndDeletedFalse(username);
    }

    /** Marks a notification as read — only if it belongs to the specified user. */
    @Transactional
    public void markiereGelesen(Long id, String username) {
        notificationRepository.findById(id)
                .filter(n -> username.equals(n.getEmpfaengerUsername()) && n.getGelesenAm() == null)
                .ifPresent(n -> {
                    n.setGelesenAm(LocalDateTime.now());
                    notificationRepository.save(n);
                });
    }

    @Transactional
    public int markiereAlleGelesen(String username) {
        List<Notification> ungelesen = notificationRepository.findByEmpfaengerUsernameAndGelesenAmIsNullAndDeletedFalse(username);
        LocalDateTime now = LocalDateTime.now();
        ungelesen.forEach(n -> n.setGelesenAm(now));
        notificationRepository.saveAll(ungelesen);
        return ungelesen.size();
    }

    /** Deletes read notifications whose {@code gelesenAm} lies before the cutoff (cleanup cron). */
    @Transactional
    public int cleanupGelesenAelterAls(LocalDateTime cutoff) {
        List<Notification> alte = notificationRepository.findByGelesenAmIsNotNullAndGelesenAmBefore(cutoff);
        if (alte.isEmpty()) {
            return 0;
        }
        notificationRepository.deleteAll(alte);
        return alte.size();
    }
}

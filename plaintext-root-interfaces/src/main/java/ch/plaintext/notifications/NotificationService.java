/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications;

import java.util.Map;

/**
 * Central in-app notification system. Implemented in {@code plaintext-admin-notifications} (root);
 * available transitively there, and in other applications (boot/guild) optionally as an
 * {@code @Autowired(required = false)} bean (analogous to {@code IMailTemplateProvider}).
 *
 * <p>Title and text are rendered through the same mechanism as mail texts
 * ({@code IMailTemplateProvider}, key namespace {@code notif.*}): the caller supplies the default
 * title and text, and a tenant-scoped admin override in the DB takes precedence.</p>
 */
public interface NotificationService {

    /**
     * Creates an in-app notification for a single user.
     *
     * @param empfaengerUsername username (login name) of the recipient
     * @param mandat             tenant under which it is rendered and stored
     * @param typ                notification type (becomes the template key {@code notif.<typ>})
     * @param defaultTitel       default title, if no admin override exists
     * @param defaultText        default text, if no admin override exists
     * @param platzhalter        {@code {key}} placeholders for title and text
     * @param link               optional target URL within the application, or {@code null}
     */
    void notify(String empfaengerUsername, String mandat, String typ, String defaultTitel, String defaultText,
                Map<String, String> platzhalter, String link);

    /**
     * Creates the same notification for all users of a tenant (broadcast).
     */
    void notifyMandant(String mandat, String typ, String defaultTitel, String defaultText,
                        Map<String, String> platzhalter, String link);
}

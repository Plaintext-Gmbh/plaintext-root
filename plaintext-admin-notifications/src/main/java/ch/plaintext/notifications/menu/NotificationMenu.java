/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

// SECURITY (Karte 308, H4): Diese Seite ist der EINZIGE Fall im Framework, in dem ein Menuepunkt
// unter dem ROOT-Elternmenue absichtlich fuer alle User erreichbar ist: includes/topbar.xhtml
// verlinkt sie ueber die Benachrichtigungs-Glocke ("Alle anzeigen") fuer JEDEN eingeloggten User;
// unter "Root" haengt sie nur aus UX-Gruenden. Die Rollen sind deshalb explizit gesetzt, damit die
// Eltern-Rollen-Vererbung des PageAccessGuardService (Modus STRICT) hier NICHT greift — sonst
// wuerde die Glocke fuer normale User ins Access-Denied laufen. Gleiches Muster wie ApiTokenMenu.
@MenuAnnotation(
    title = "Benachrichtigungen",
    link = "notifications.html",
    parent = "Root",
    order = 12,
    icon = "pi pi-bell",
    roles = {"USER", "ADMIN", "ROOT"},
    moduleId = "notifications"
)
public class NotificationMenu {
}

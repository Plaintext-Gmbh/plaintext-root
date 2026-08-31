/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.notifications.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

// SECURITY (card 308, H4): this page is the ONLY case in the framework where a menu item below
// the ROOT parent menu is deliberately reachable for all users: includes/topbar.xhtml links to it
// from the notification bell ("Alle anzeigen") for EVERY logged-in user; it only hangs below
// "Root" for UX reasons. The roles are therefore set explicitly so that the parent role
// inheritance of the PageAccessGuardService (STRICT mode) does NOT apply here — otherwise the
// bell would run into access-denied for normal users. Same pattern as ApiTokenMenu.
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

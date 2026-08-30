/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

    // SECURITY (card 308, H4): explicit roles. Without them MenuItemImpl.isOn() checks nothing —
    // and because the PageAccessGuardService only evaluates isOn() of THIS entry (not that of
    // the parent menu), the page was open via direct URL to every logged-in USER, although it
    // hangs in the rendered menu only below the ADMIN parent menu. Does not change how the menu
    // is rendered: for non-ADMIN the parent menu was already not rendered before.
@MenuAnnotation(
    title = "Sessions",
    link = "sessions.html",
    parent = "Admin",
    order = 4,
    icon = "pi pi-users",
    roles = {"ADMIN", "ROOT"}
)
public class SessionsSubmenu {
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

    // SECURITY (Card 308, H4): explicit roles. Without them MenuItemImpl.isOn() checks nothing —
    // and because PageAccessGuardService only evaluates isOn() of THIS entry (not the one of the
    // parent menu), the page was reachable by direct URL for every logged-in USER, even though in
    // the rendered menu it only hangs below the ADMIN parent menu. Does not change how the menu is
    // rendered: for non-ADMIN users the parent menu was never rendered in the first place.
@MenuAnnotation(
    title = "Rollenzuteilung",
    link = "rollenzuteilung.html",
    parent = "Admin",
    order = 5,
    icon = "pi pi-users",
    roles = {"ADMIN", "ROOT"}
)
public class RollenzuteilungSubmenu {
}

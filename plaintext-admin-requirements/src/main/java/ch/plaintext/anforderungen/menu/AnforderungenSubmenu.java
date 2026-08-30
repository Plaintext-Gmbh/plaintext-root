/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

    // SECURITY (card 308, H4): explicit roles. Without them MenuItemImpl.isOn() checks nothing —
    // and because the PageAccessGuardService only evaluates isOn() of THIS entry (not the one of
    // the parent menu), the page was reachable by direct URL for every logged-in USER, although in
    // the rendered menu it only hangs below the ADMIN parent menu. Does not change how the menu is
    // rendered: for non-ADMIN the parent menu was not rendered before either.
@MenuAnnotation(
    title = "Liste",
    link = "anforderungen.html",
    parent = "Anforderungen",
    order = 1,
    icon = "pi pi-list",
    roles = {"ADMIN", "ROOT"}
)
public class AnforderungenSubmenu {
}

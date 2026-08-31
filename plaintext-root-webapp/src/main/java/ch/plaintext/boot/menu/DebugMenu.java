/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

@MenuAnnotation(
    title = "Debug",
    link = "debug.html",
    parent = "Admin",
    order = 50,
    icon = "pi pi-wrench",
    // Status report 29.08.2026 (H4), found by SeitenrechteInvariantTest: ADMIN_PAGES permits
    // ADMIN|ROOT, the menu entry showed only ADMIN — a pure ROOT did not see the page.
    roles = {"ADMIN", "ROOT"}
)
public class DebugMenu {

}

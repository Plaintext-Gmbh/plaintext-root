/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.boot.menu.MenuAnnotation;

@MenuAnnotation(
    title = "Cron",
    link = "cron.html",
    order = 10,
    parent = "Admin",
    icon = "pi pi-calendar-times",
    // Status report 29.08.2026 (H4): ADMIN_PAGES in PlaintextSecurityConfig allows ADMIN|ROOT,
    // the menu entry only ADMIN — a pure ROOT got through Spring and was rejected by the menu
    // guard. Both places now say the same thing.
    roles = {"ADMIN", "ROOT"},
    moduleId = "cron"
)
public class CronMenu {

}

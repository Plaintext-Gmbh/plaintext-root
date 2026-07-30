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
    roles = {"ADMIN"},
    moduleId = "cron"
)
public class CronMenu {

}

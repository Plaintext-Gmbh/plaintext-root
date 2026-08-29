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
    // Zustandsbericht 29.08.2026 (H4), gefunden von SeitenrechteInvariantTest: ADMIN_PAGES erlaubt
    // ADMIN|ROOT, der Menuepunkt zeigte nur ADMIN — ein reiner ROOT sah die Seite nicht.
    roles = {"ADMIN", "ROOT"}
)
public class DebugMenu {

}

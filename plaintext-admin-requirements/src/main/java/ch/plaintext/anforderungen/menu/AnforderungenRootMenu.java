/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

@MenuAnnotation(
    title = "Anforderungen",
    order = 100,
    icon = "pi pi-list-check",
    roles = {"ADMIN", "ROOT"}
)
public class AnforderungenRootMenu {
}

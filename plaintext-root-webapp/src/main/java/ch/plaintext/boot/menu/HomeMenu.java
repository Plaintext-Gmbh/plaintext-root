/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Home menu item
 */
@MenuAnnotation(
    title = "Home",
    link = "index.html",
    order = 10,
    icon = "pi pi-home"
)
public class HomeMenu {
}

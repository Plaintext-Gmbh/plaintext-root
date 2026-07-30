/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

/**
 * Home menu item
 */
@MenuAnnotation(
    title = "Admin",
    link = "index.html",
    parent = "",
    order = 2,
    icon = "pi pi-cog",
    roles = {"ADMIN"}
)
public class AdminSuperMenu {

}

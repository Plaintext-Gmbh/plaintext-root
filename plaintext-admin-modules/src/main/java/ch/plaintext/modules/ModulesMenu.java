/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import ch.plaintext.boot.menu.MenuAnnotation;

/** "Module" menu item (Root, ROOT only) — list feature modules, version, switch on/off. */
@MenuAnnotation(
    title = "Module",
    link = "module.html",
    order = 96,
    parent = "Root",
    icon = "pi pi-th-large",
    roles = {"ROOT"}
)
public class ModulesMenu {
}

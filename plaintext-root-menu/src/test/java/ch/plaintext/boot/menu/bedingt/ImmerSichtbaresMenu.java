/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu.bedingt;

import ch.plaintext.boot.menu.MenuAnnotation;

/** Test fixture: a menu item without a condition — always found. */
@MenuAnnotation(title = "Immer da", link = "immer.html", parent = "Root", order = 1)
public class ImmerSichtbaresMenu {
}

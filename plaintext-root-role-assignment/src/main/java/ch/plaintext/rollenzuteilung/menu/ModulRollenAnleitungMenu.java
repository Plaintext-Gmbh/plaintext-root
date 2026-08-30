/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Submenu "Anleitung Modul-Rollen" — the guide for admins: how to grant a module role to a user,
 * what it does, how it interacts with root's tenant lists and how to recognise that you actually
 * need root.
 *
 * <p>Visible for {@code ADMIN} and {@code ROOT}; sits at the very bottom of the Admin branch.</p>
 *
 * @author info@plaintext.ch
 * @since 1.608.0
 */
@MenuAnnotation(
        title = "Anleitung Modul-Rollen",
        link = "modulrollen-anleitung.html",
        parent = "Admin",
        order = 900,
        icon = "pi pi-question-circle",
        roles = {"ADMIN", "ROOT"}
)
public class ModulRollenAnleitungMenu {
}

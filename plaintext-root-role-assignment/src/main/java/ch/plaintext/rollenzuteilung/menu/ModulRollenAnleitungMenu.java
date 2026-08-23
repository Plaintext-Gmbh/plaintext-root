/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Untermenue „Anleitung Modul-Rollen" — die Anleitung fuer admin: eine Modul-Rolle an einen
 * Benutzer vergeben, was sie bewirkt, wie sie mit den Mandanten-Listen von root zusammenspielt und
 * woran man erkennt, dass man root braucht.
 *
 * <p>Sichtbar fuer {@code ADMIN} und {@code ROOT}; steht zuunterst im Admin-Zweig.</p>
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

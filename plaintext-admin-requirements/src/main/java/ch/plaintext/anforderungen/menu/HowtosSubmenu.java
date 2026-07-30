/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

    // SECURITY (Karte 308, H4): explizite roles. Ohne sie prueft MenuItemImpl.isOn() nichts —
    // und weil der PageAccessGuardService nur isOn() DIESES Eintrags auswertet (nicht die des
    // Elternmenues), war die Seite per Direkt-URL fuer jeden eingeloggten USER offen, obwohl sie
    // im gerenderten Menue nur unter dem ADMIN-Elternmenue haengt. Aendert die Menue-Darstellung
    // nicht: fuer Nicht-ADMIN wurde das Elternmenue schon vorher nicht gerendert.
@MenuAnnotation(
    title = "Howtos",
    link = "howtos.html",
    parent = "Anforderungen",
    order = 2,
    icon = "pi pi-book",
    roles = {"ADMIN", "ROOT"}
)
public class HowtosSubmenu {
}

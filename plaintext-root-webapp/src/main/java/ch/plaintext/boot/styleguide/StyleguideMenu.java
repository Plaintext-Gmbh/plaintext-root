/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.styleguide;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Karte 1548: der Styleguide — die lebende Referenz der UI-2-Grundlage (Tokens, Muster,
 * Zustaende). Unter "Admin", weil er sich an die richtet, die Seiten bauen, nicht an die, die
 * sie benutzen; dieselben Rollen wie {@code /styleguide.*} in {@code ADMIN_PAGES}
 * ({@code SeitenrechteInvariantTest}).
 */
@MenuAnnotation(
    title = "Styleguide",
    link = "styleguide.html",
    parent = "Admin",
    order = 96,
    icon = "pi pi-palette",
    roles = {"ADMIN", "ROOT"}
)
public class StyleguideMenu {

}

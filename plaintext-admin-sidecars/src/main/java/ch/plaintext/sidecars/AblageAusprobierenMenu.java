/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars;

import ch.plaintext.boot.menu.MenuAnnotation;

/** Karte 1440: Testseite für das Ablage-UI {@code pt:dateiAblage}, ADMIN und ROOT. */
@MenuAnnotation(
    title = "Ablage ausprobieren",
    link = "ablage-ausprobieren.html",
    order = 97,
    parent = "Admin",
    icon = "pi pi-folder-open",
    roles = {"ADMIN", "ROOT"},
    moduleId = "sidecars"
)
public class AblageAusprobierenMenu {
}

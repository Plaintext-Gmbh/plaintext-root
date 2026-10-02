/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars;

import ch.plaintext.boot.menu.MenuAnnotation;

/** Karte 1400: Übersicht der Sidecars, nur ROOT (instanzweite Container, Tokens). */
@MenuAnnotation(
    title = "Sidecars",
    link = "sidecars.html",
    order = 96,
    parent = "Root",
    icon = "pi pi-box",
    roles = {"ROOT"},
    moduleId = "sidecars"
)
public class SidecarsMenu {
}

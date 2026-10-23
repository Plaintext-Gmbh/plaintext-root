/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files.web;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Persönliche Dateiablage (Karte 1559). Im Inventar hing der Eintrag unter «Marthaler»; wo er steht, entscheidet die
 * Anwendung über die Menü-Sichtbarkeit des Moduls {@code files}.
 */
@MenuAnnotation(
        title = "Files",
        link = "files/ablage.html",
        order = 65,
        icon = "pi pi-folder-open",
        moduleId = "files",
        roles = {"USER", "ADMIN", "ROOT"}
)
public class FilesMenu {
}

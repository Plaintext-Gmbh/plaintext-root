/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.laufzeit;

import ch.plaintext.boot.menu.MenuAnnotation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Karte 1608: Seite «Laufzeit-Module» (Auswahl aus signierten Registries). ADMIN sieht, ROOT schaltet.
 * Nur mit {@code plaintext.module.auswahl=true}: Instanzen ohne Auswahl-Weg sehen die Seite nicht (ohne Menue
 * sperrt der Page-Guard sie auch).
 */
@ConditionalOnProperty(name = "plaintext.module.auswahl", havingValue = "true")
@MenuAnnotation(
    title = "Laufzeit-Module",
    link = "modulauswahl.html",
    order = 97,
    parent = "Admin",
    icon = "pi pi-box",
    roles = {"ADMIN", "ROOT"}
)
public class ModulAuswahlMenu {
}

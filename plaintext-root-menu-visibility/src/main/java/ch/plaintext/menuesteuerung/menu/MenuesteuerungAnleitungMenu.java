/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Untermenue „Anleitung Menuesteuerung" — die Anleitung fuer root: Mandanten-Listen (White- vs.
 * Blacklist), Modul-Eintrag vs. Titel-Eintrag, die Diagnose-Ansicht lesen, Impersonate.
 *
 * <p>Nur fuer {@code ROOT} sichtbar; die Seite verweist auf Dinge, die admin nicht sieht. Steht
 * bewusst zuunterst im Root-Zweig als Nachschlagewerk.</p>
 *
 * @author info@plaintext.ch
 * @since 1.608.0
 */
@MenuAnnotation(
        title = "Anleitung Menüsteuerung",
        link = "menuesteuerung-anleitung.html",
        parent = "Root",
        order = 900,
        icon = "pi pi-question-circle",
        roles = {"ROOT"}
)
public class MenuesteuerungAnleitungMenu {
}

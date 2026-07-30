/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

    // SECURITY (Karte 308, H4): explizite roles. Ohne sie prueft MenuItemImpl.isOn() nichts —
    // und weil der PageAccessGuardService nur isOn() DIESES Eintrags auswertet (nicht die des
    // Elternmenues), war die Seite per Direkt-URL fuer jeden eingeloggten USER offen, obwohl sie
    // im gerenderten Menue nur unter dem ROOT-Elternmenue haengt. Aendert die Menue-Darstellung
    // nicht: fuer Nicht-ROOT wurde das Elternmenue schon vorher nicht gerendert.
@MenuAnnotation(
    title = "Webhooks",
    link = "webhooks.html",
    parent = "Root",
    order = 12,
    icon = "pi pi-bolt",
    roles = {"ROOT"},
    moduleId = "webhooks"
)
public class WebhookMenu {
}

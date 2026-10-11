/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe.link.web;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Übersicht der Freigabe-Links des Mandats (Karte 1476). Jeder sieht nur Links auf Objekte, die er selbst
 * freigeben darf; das prüft das Modul des Objekts.
 */
@MenuAnnotation(
        title = "Freigabe-Links",
        link = "freigabe-links.html",
        order = 92,
        parent = "Admin",
        icon = "pi pi-share-alt",
        roles = {"USER", "ADMIN", "ROOT"}
)
public class FreigabeLinkMenu {
}

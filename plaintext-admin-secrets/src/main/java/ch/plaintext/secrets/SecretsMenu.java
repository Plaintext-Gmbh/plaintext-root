/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Menüpunkt „Secrets" (Admin, nur ROOT) — Liste + Settings + Generator auf einer Seite.
 */
@MenuAnnotation(
    title = "Secrets",
    link = "secrets.html",
    order = 95,
    parent = "Root",
    icon = "pi pi-lock",
    roles = {"ROOT"},
    moduleId = "secrets"
)
public class SecretsMenu {
}

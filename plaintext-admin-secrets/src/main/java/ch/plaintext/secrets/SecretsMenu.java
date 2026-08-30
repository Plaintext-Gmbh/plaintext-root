/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Menu item "Secrets" (admin, ROOT only) — list + settings + generator on a single page.
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

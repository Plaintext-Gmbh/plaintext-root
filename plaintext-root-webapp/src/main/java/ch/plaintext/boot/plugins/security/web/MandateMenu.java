/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Mandate Menu
 *
 * @author info@plaintext.ch
 * @since 2024
 */
@MenuAnnotation(
    title = "Mandate",
    link = "mandate.html",
    parent = "Root",
    order = 61,
    icon = "pi pi-list",
    roles = {"ROOT"}
)
public class MandateMenu {

}

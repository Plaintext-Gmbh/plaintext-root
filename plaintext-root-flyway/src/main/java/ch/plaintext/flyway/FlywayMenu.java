/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.flyway;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Flyway Menu
 *
 * @author plaintext.ch
 * @since 1.108.0
 */
@MenuAnnotation(
    title = "Flyway",
    link = "flyway.html",
    parent = "Root",
    order = 100,
    icon = "pi pi-database",
    roles = {"ROOT"}
)
public class FlywayMenu {

}

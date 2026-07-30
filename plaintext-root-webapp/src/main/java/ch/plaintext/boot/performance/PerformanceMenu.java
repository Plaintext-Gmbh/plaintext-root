/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.performance;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Performance menu item
 */
@MenuAnnotation(
    title = "Performance",
    link = "performance.html",
    parent = "Root",
    order = 1,
    icon = "pi pi-prime",
    roles = {"ROOT"}
)
public class PerformanceMenu {

}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink.web;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Menu entry "deep links" (root) — overview of the registered deep-link targets (card 345).
 * ROOT only, additionally secured explicitly in {@code PlaintextSecurityConfig.ROOT_ONLY_PAGES}.
 */
@MenuAnnotation(
        title = "Deep-Links",
        link = "deeplinks.html",
        order = 96,
        parent = "Root",
        icon = "pi pi-link",
        roles = {"ROOT"},
        moduleId = "deeplinks"
)
public class DeepLinkMenu {
}

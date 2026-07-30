/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.oidc.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

@MenuAnnotation(
        title = "OIDC Login",
        link = "oidcconfig.html",
        parent = "Root",
        order = 3,
        icon = "pi pi-key",
        roles = {"root"}
)
public class OidcConfigSubmenu {
}

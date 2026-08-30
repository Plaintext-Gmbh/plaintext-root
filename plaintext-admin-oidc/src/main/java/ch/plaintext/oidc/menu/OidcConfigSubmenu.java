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
        // Status report 29.08.2026 (H4): "root" was spelled in lower case — the role comparison is
        // case-sensitive (ROLE_ROOT), so the page was reachable for NOBODY. SeitenrechteInvariantTest
        // now pins the spelling down.
        roles = {"ROOT"}
)
public class OidcConfigSubmenu {
}

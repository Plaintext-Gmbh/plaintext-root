/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

    // SECURITY (card 308, H4): explicit roles. Without them MenuItemImpl.isOn() checks nothing —
    // and because the PageAccessGuardService only evaluates isOn() of THIS entry (not that of the
    // parent menu), the page was open via a direct URL to every logged-in USER, even though in the
    // rendered menu it only hangs below the ROOT parent menu. Does not change the menu rendering:
    // for non-ROOT the parent menu was not rendered before either.
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

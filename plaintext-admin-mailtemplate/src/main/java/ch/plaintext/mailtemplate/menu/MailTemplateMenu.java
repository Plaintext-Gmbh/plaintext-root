/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * "Mailtexte" menu item in the <b>Admin</b> submenu (Daniel's request, 29.08.2026 — previously Root).
 *
 * <p>The overrides apply per tenant, and the Backing Bean works only on the tenant of the session
 * anyway ({@code PlaintextSecurityHolder.getMandat()}). An admin should be able to maintain the
 * mail texts of their own tenant without having to bother root. The page is hard-wired to
 * ADMIN/ROOT in {@code PlaintextSecurityConfig.ADMIN_PAGES}.</p>
 *
 * <p>SECURITY (card 308, H4): explicit {@code roles}. Without them {@code MenuItemImpl.isOn()}
 * checks nothing, and the PageAccessGuardService only evaluates this entry, not the parent menu.</p>
 */
@MenuAnnotation(
    title = "Mailtexte",
    link = "mailtemplates.html",
    parent = "Admin",
    order = 60,
    icon = "pi pi-envelope",
    roles = {"ADMIN", "ROOT"},
    moduleId = "mailtemplate"
)
public class MailTemplateMenu {
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailtemplate.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Menuepunkt „Mailtexte" im Untermenue <b>Admin</b> (Auftrag Daniel, 29.08.2026 — vorher Root).
 *
 * <p>Die Overrides gelten je Mandant, und der Backing-Bean arbeitet ohnehin nur auf dem Mandanten
 * der Session ({@code PlaintextSecurityHolder.getMandat()}). Ein admin soll die Mailtexte seines
 * Mandanten selbst pflegen koennen, ohne root zu bemuehen. Die Seite ist in
 * {@code PlaintextSecurityConfig.ADMIN_PAGES} hart auf ADMIN/ROOT verdrahtet.</p>
 *
 * <p>SECURITY (Karte 308, H4): explizite {@code roles}. Ohne sie prueft {@code MenuItemImpl.isOn()}
 * nichts, und der PageAccessGuardService wertet nur diesen Eintrag aus, nicht das Elternmenue.</p>
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

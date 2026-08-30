/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

    // SECURITY (card 308, H4): explicit roles. Without them MenuItemImpl.isOn() checks nothing —
    // and because PageAccessGuardService only evaluates isOn() of THIS entry (not that of the
    // parent menu), the page was open by direct URL to every logged-in USER, even though in the
    // rendered menu it hangs only under the ROOT parent menu. Does not change the menu display:
    // for non-ROOT the parent menu was not rendered before either.
@MenuAnnotation(
    title = "Übersetzungen",
    link = "i18n-translations.html",
    parent = "Root",
    order = 10,
    icon = "pi pi-language",
    roles = {"ADMIN", "ROOT"},
    moduleId = "i18n"
)
public class I18nTranslationsMenu {
}

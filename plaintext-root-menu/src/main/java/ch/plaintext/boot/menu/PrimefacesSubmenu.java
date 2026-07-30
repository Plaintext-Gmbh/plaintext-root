/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.I18nProvider;
import org.primefaces.model.menu.DefaultSubMenu;

import java.util.function.Supplier;

/**
 * PrimeFaces DefaultSubMenu wrapper for AbstractMenuItem
 */
public class PrimefacesSubmenu extends DefaultSubMenu {

    private final AbstractMenuItem item;
    private I18nProvider i18nProvider;
    private Supplier<String> languageSupplier;

    public PrimefacesSubmenu(AbstractMenuItem item) {
        this.item = item;
        this.setLabel(item.getTitle());
        this.setIcon(item.getIc());
    }

    public void setI18nProvider(I18nProvider i18nProvider) {
        this.i18nProvider = i18nProvider;
    }

    public void setLanguageSupplier(Supplier<String> languageSupplier) {
        this.languageSupplier = languageSupplier;
    }

    @Override
    public String getLabel() {
        String title = item.getTitle();
        if (i18nProvider != null && i18nProvider.isI18nEnabled() && languageSupplier != null) {
            String lang = languageSupplier.get();
            if (lang != null && !"de".equals(lang)) {
                return i18nProvider.translate(title, lang);
            }
        }
        return title;
    }

    @Override
    public boolean isRendered() {
        return item.isOn();
    }

    /**
     * Get the menu item implementation
     */
    public MenuItemImpl getMenuItem() {
        if (item instanceof MenuItemImpl) {
            return (MenuItemImpl) item;
        }
        return null;
    }
}

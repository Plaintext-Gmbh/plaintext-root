/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.util.Collection;

/**
 * Interface for providing menu visibility rules based on mandate-specific configuration.
 * <p>
 * This interface allows optional integration of menu visibility control.
 * If no implementation is found in the Spring context, the menu system will work as before.
 * If an implementation is present, it will be consulted to determine if a menu item should be visible
 * for the current mandate.
 * </p>
 *
 * @author plaintext.ch
 * @since 1.39.0
 */
public interface MenuVisibilityProvider {

    /**
     * Checks if a menu item should be visible for the current mandate.
     *
     * @param menuTitle the full menu title (e.g., "Root | Mandate" or "Zeiterfassung")
     * @return true if the menu should be visible, false if it should be hidden
     */
    boolean isMenuVisible(String menuTitle);

    /**
     * Checks if a menu item should be visible for a specific mandate.
     *
     * @param menuTitle the full menu title (e.g., "Root | Mandate" or "Zeiterfassung")
     * @param mandate the mandate name
     * @return true if the menu should be visible, false if it should be hidden
     */
    boolean isMenuVisibleForMandate(String menuTitle, String mandate);

    /**
     * Like {@link #isMenuVisible(String)}, but additionally with the module keys of the menu item:
     * the tenant's list may hold an entry as a <b>module</b> rather than as a menu title and
     * thereby switch an entire module with a single entry.
     *
     * <p>The default delegates to the title variant — every existing implementation therefore
     * behaves unchanged.</p>
     *
     * @param menuTitle  the full menu title (e.g. {@code "Root | Menüsteuerung"})
     * @param moduleKeys the module keys of the menu item (its own {@code moduleId}, those of the
     *                   parent menus, and the menu root id), may be empty or {@code null}
     * @return {@code true} if the menu item is visible for the current tenant
     * @since 1.608.0
     */
    default boolean isMenuVisible(String menuTitle, Collection<String> moduleKeys) {
        return isMenuVisible(menuTitle);
    }

    /**
     * Like {@link #isMenuVisibleForMandate(String, String)}, but additionally with the module keys
     * of the menu item. The default delegates to the title variant.
     *
     * @param menuTitle  the full menu title (e.g. {@code "Root | Menüsteuerung"})
     * @param moduleKeys the module keys of the menu item, may be empty or {@code null}
     * @param mandate    the tenant
     * @return {@code true} if the menu item is visible for this tenant
     * @since 1.608.0
     */
    default boolean isMenuVisibleForMandate(String menuTitle, Collection<String> moduleKeys, String mandate) {
        return isMenuVisibleForMandate(menuTitle, mandate);
    }
}

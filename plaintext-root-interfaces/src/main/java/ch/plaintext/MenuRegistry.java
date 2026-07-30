/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.util.List;

/**
 * Interface for accessing registered menu items in the application.
 * <p>
 * This interface provides methods to query all available menu items,
 * which can be used for configuration UIs or admin panels.
 * </p>
 *
 * @author plaintext.ch
 * @since 1.42.0
 */
public interface MenuRegistry {

    /**
     * Gets all registered menu titles.
     * Returns the full titles including parent hierarchy.
     * E.g., "Root | Mandate", "Zeiterfassung | Zeiterfassung Einstellungen"
     *
     * @return list of all menu titles
     */
    List<String> getAllMenuTitles();

    /**
     * Gets all registered menu items with their full details.
     *
     * @return list of all menu items
     */
    List<MenuItem> getAllMenuItems();

    /**
     * Represents a menu item with its properties.
     */
    interface MenuItem {
        /**
         * Gets the title/label of this menu item.
         *
         * @return the menu item title
         */
        String getTitle();

        /**
         * Gets the parent menu item identifier.
         *
         * @return the parent menu title, or empty string for root-level items
         */
        String getParent();

        /**
         * Gets the full title including the parent hierarchy.
         *
         * @return the full menu title (e.g. "Root | Mandate")
         */
        String getFullTitle();

        /**
         * Gets the icon class for the menu item.
         *
         * @return the PrimeFaces icon class, or empty string if none
         */
        String getIcon();

        /**
         * Gets the navigation link/URL for the menu item.
         *
         * @return the link URL
         */
        String getLink();

        /**
         * Gets the display order of this menu item among its siblings.
         *
         * @return the order value (lower numbers appear first)
         */
        int getOrder();

        /**
         * Gets the roles that are allowed to see this menu item.
         *
         * @return list of role names, or empty list if visible to all
         */
        List<String> getRoles();

        /**
         * Checks if this menu item should be visible/accessible to the current user.
         * This combines both role-based and mandate-based visibility checks.
         *
         * @return true if the menu item is visible, false otherwise
         */
        boolean isOn();
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

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
}

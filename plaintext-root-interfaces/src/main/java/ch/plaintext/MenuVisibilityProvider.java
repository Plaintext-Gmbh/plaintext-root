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
     * Wie {@link #isMenuVisible(String)}, zusaetzlich mit den Modul-Keys des Menuepunkts: die
     * Mandanten-Liste darf einen Eintrag auch als <b>Modul</b> statt als Menue-Titel fuehren und
     * damit ein ganzes Modul mit einem Eintrag schalten.
     *
     * <p>Der Default delegiert auf die Titel-Variante — jede bestehende Implementierung verhaelt
     * sich damit unveraendert.</p>
     *
     * @param menuTitle  der volle Menue-Titel (z.B. {@code "Root | Menüsteuerung"})
     * @param moduleKeys die Modul-Keys des Menuepunkts (eigene {@code moduleId}, die der
     *                   Elternmenues und die Menu-Root-Id), darf leer oder {@code null} sein
     * @return {@code true}, wenn der Menuepunkt fuer den aktuellen Mandanten sichtbar ist
     * @since 1.608.0
     */
    default boolean isMenuVisible(String menuTitle, Collection<String> moduleKeys) {
        return isMenuVisible(menuTitle);
    }

    /**
     * Wie {@link #isMenuVisibleForMandate(String, String)}, zusaetzlich mit den Modul-Keys des
     * Menuepunkts. Der Default delegiert auf die Titel-Variante.
     *
     * @param menuTitle  der volle Menue-Titel (z.B. {@code "Root | Menüsteuerung"})
     * @param moduleKeys die Modul-Keys des Menuepunkts, darf leer oder {@code null} sein
     * @param mandate    der Mandant
     * @return {@code true}, wenn der Menuepunkt fuer diesen Mandanten sichtbar ist
     * @since 1.608.0
     */
    default boolean isMenuVisibleForMandate(String menuTitle, Collection<String> moduleKeys, String mandate) {
        return isMenuVisibleForMandate(menuTitle, mandate);
    }
}

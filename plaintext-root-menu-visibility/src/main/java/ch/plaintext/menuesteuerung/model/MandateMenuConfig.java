/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.model;

import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

/**
 * Entity representing mandate-specific menu configuration.
 * Stores which menu items should be hidden for each mandate.
 *
 * @author plaintext.ch
 * @since 1.39.0
 */
@Entity
@Table(name = "mandate_menu_config")
@Data
public class MandateMenuConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The mandate name (e.g., "default", "mandate1", etc.)
     */
    @Column(nullable = false, unique = true)
    private String mandateName;

    /**
     * Set of hidden menu titles for this mandate.
     * Menu titles are stored with their full hierarchy, e.g., "Root | Mandate" or "Zeiterfassung"
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mandate_hidden_menus", joinColumns = @JoinColumn(name = "config_id"))
    @Column(name = "menu_title")
    private Set<String> hiddenMenus = new HashSet<>();

    /**
     * Determines if the mode is whitelist (true) or blacklist (false).
     * - Blacklist mode (default, false): hiddenMenus contains items to hide
     * - Whitelist mode (true): hiddenMenus contains items to show (all others are hidden)
     */
    @Column(name = "is_whitelist_mode")
    private Boolean whitelistMode = false;

    /**
     * Checks if a menu is hidden for this mandate.
     *
     * @param menuTitle the full menu title
     * @return true if hidden, false otherwise
     */
    public boolean isMenuHidden(String menuTitle) {
        return hiddenMenus != null && hiddenMenus.contains(menuTitle);
    }

    /**
     * Hides a menu for this mandate.
     *
     * @param menuTitle the full menu title to hide
     */
    public void hideMenu(String menuTitle) {
        if (hiddenMenus == null) {
            hiddenMenus = new HashSet<>();
        }
        hiddenMenus.add(menuTitle);
    }

    /**
     * Shows a menu for this mandate (removes it from hidden list).
     *
     * @param menuTitle the full menu title to show
     */
    public void showMenu(String menuTitle) {
        if (hiddenMenus != null) {
            hiddenMenus.remove(menuTitle);
        }
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.model;

import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Entity representing mandate-specific menu configuration.
 * Stores which menu items should be hidden for each mandate.
 *
 * <p><b>Two forms of list entries</b> (since 1.608.0):</p>
 * <ul>
 *   <li><b>Menu title</b> — the full title {@code "Parent | Titel"} or just {@code "Titel"}. The
 *       historical and still valid form; one entry switches exactly one menu item.</li>
 *   <li><b>Module key</b> — {@code "modul:<moduleId>"}. One entry switches the whole module, and
 *       therefore all submenus too, without having to maintain every single title.</li>
 * </ul>
 *
 * <p><b>Why the prefix {@code modul:} and not the bare {@code moduleId}.</b> The migration has to
 * be lossless: no stored entry may change its meaning. Bare keys could collide — a module with the
 * {@code moduleId} {@code wiki} usually has its root menu under the title {@code Wiki}, and the
 * existing data also contains entries that no longer match any title (renames). A bare key would
 * therefore be a title sometimes and a module other times, depending on the state of the menu tree.
 * The prefix makes the intent visible on the entry itself: everything without it is and stays a
 * title, and because no menu title begins with {@code "modul:"}, no existing entry can accidentally
 * turn into a module entry.</p>
 *
 * @author plaintext.ch
 * @since 1.39.0
 */
@Entity
@Table(name = "mandate_menu_config")
@Data
public class MandateMenuConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Prefix that marks a list entry as a module key rather than a menu title. */
    public static final String MODULE_PREFIX = "modul:";

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

    /**
     * Is this menu item on the list — as a menu title <b>or</b> through one of its module keys?
     *
     * <p>The title check remains exact (case and whitespace matter), so that no existing entry
     * changes its effect.</p>
     *
     * @param menuTitle  full menu title
     * @param moduleKeys module keys of the menu item, may be empty or {@code null}
     * @return {@code true} if the menu item is covered by the list
     * @since 1.608.0
     */
    public boolean isListed(String menuTitle, Collection<String> moduleKeys) {
        return isMenuHidden(menuTitle) || containsAnyModuleKey(moduleKeys);
    }

    /**
     * Does the list contain a {@code modul:} entry that matches one of these keys?
     *
     * @param moduleKeys module keys to check, may be empty or {@code null}
     * @return {@code true} on a match
     * @since 1.608.0
     */
    public boolean containsAnyModuleKey(Collection<String> moduleKeys) {
        if (moduleKeys == null || moduleKeys.isEmpty() || hiddenMenus == null) {
            return false;
        }
        Set<String> configured = getModuleKeyEntries();
        if (configured.isEmpty()) {
            return false;
        }
        for (String key : moduleKeys) {
            if (configured.contains(canonicalModuleKey(key))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The module keys of the list (canonical, without prefix), alphabetically.
     *
     * @return module keys of the {@code modul:} entries (never {@code null})
     * @since 1.608.0
     */
    public Set<String> getModuleKeyEntries() {
        Set<String> ret = new TreeSet<>();
        if (hiddenMenus == null) {
            return ret;
        }
        for (String entry : hiddenMenus) {
            String key = moduleKeyOf(entry);
            if (!key.isEmpty()) {
                ret.add(key);
            }
        }
        return ret;
    }

    /**
     * The title entries of the list — everything that is not a {@code modul:} entry.
     *
     * @return menu titles of the list in insertion order (never {@code null})
     * @since 1.608.0
     */
    public Set<String> getTitleEntries() {
        Set<String> ret = new LinkedHashSet<>();
        if (hiddenMenus == null) {
            return ret;
        }
        for (String entry : hiddenMenus) {
            if (entry != null && moduleKeyOf(entry).isEmpty()) {
                ret.add(entry);
            }
        }
        return ret;
    }

    /**
     * The module key of a list entry — or empty when the entry is a menu title.
     *
     * @param entry list entry, may be {@code null}
     * @return canonical module key without the prefix, otherwise {@code ""}
     * @since 1.608.0
     */
    public static String moduleKeyOf(String entry) {
        if (entry == null) {
            return "";
        }
        String value = entry.trim();
        if (value.length() <= MODULE_PREFIX.length()
                || !value.regionMatches(true, 0, MODULE_PREFIX, 0, MODULE_PREFIX.length())) {
            return "";
        }
        return canonicalModuleKey(value.substring(MODULE_PREFIX.length()));
    }

    /**
     * The list entry for a module key: {@code "modul:<key>"}.
     *
     * @param moduleKey module key, may be {@code null}
     * @return list entry, or {@code ""} for an empty key
     * @since 1.608.0
     */
    public static String moduleEntryOf(String moduleKey) {
        String key = canonicalModuleKey(moduleKey);
        return key.isEmpty() ? "" : MODULE_PREFIX + key;
    }

    /**
     * Canonical form of a module key: trimmed and lower-cased — the same normalisation as for the
     * module roles ({@code ModuleRoleProperties.canonicalKey}).
     *
     * @param moduleKey raw key, may be {@code null}
     * @return canonical key, never {@code null}
     * @since 1.608.0
     */
    public static String canonicalModuleKey(String moduleKey) {
        return moduleKey == null ? "" : moduleKey.trim().toLowerCase(Locale.ROOT);
    }
}

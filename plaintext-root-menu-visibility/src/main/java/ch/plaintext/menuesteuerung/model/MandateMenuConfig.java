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
 * <p><b>Zwei Formen von Listen-Eintraegen</b> (seit 1.608.0):</p>
 * <ul>
 *   <li><b>Menue-Titel</b> — der volle Titel {@code "Parent | Titel"} bzw. {@code "Titel"}. Die
 *       historische und weiterhin gueltige Form; ein Eintrag schaltet genau einen Menuepunkt.</li>
 *   <li><b>Modul-Key</b> — {@code "modul:<moduleId>"}. Ein Eintrag schaltet das ganze Modul,
 *       also auch alle Untermenues, ohne dass jeder Titel einzeln gepflegt werden muss.</li>
 * </ul>
 *
 * <p><b>Warum der Praefix {@code modul:} und nicht die nackte {@code moduleId}.</b> Die Umstellung
 * muss verlustfrei sein: kein gespeicherter Eintrag darf seine Bedeutung aendern. Nackte Keys
 * koennten kollidieren — ein Modul mit der {@code moduleId} {@code wiki} hat sein Wurzelmenue in
 * aller Regel unter dem Titel {@code Wiki}, und im Bestand stehen ausserdem Eintraege, die auf
 * keinen Titel mehr passen (Umbenennungen). Ein nackter Key waere damit mal Titel, mal Modul, je
 * nach Zustand des Menuebaums. Der Praefix macht die Absicht am Eintrag selbst sichtbar: alles
 * ohne ihn ist und bleibt ein Titel, und weil kein Menue-Titel mit {@code "modul:"} beginnt, kann
 * kein Bestandseintrag versehentlich zum Modul-Eintrag werden.</p>
 *
 * @author plaintext.ch
 * @since 1.39.0
 */
@Entity
@Table(name = "mandate_menu_config")
@Data
public class MandateMenuConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Praefix, der einen Listen-Eintrag als Modul-Key statt als Menue-Titel ausweist. */
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
     * Steht dieser Menuepunkt in der Liste — als Menue-Titel <b>oder</b> ueber einen seiner
     * Modul-Keys?
     *
     * <p>Die Titel-Pruefung ist unveraendert exakt (Gross-/Kleinschreibung und Leerzeichen zaehlen),
     * damit kein Bestandseintrag seine Wirkung aendert.</p>
     *
     * @param menuTitle  voller Menue-Titel
     * @param moduleKeys Modul-Keys des Menuepunkts, darf leer oder {@code null} sein
     * @return {@code true}, wenn der Menuepunkt von der Liste erfasst wird
     * @since 1.608.0
     */
    public boolean isListed(String menuTitle, Collection<String> moduleKeys) {
        return isMenuHidden(menuTitle) || containsAnyModuleKey(moduleKeys);
    }

    /**
     * Enthaelt die Liste einen {@code modul:}-Eintrag, der auf einen dieser Keys passt?
     *
     * @param moduleKeys zu pruefende Modul-Keys, darf leer oder {@code null} sein
     * @return {@code true} bei einem Treffer
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
     * Die Modul-Keys der Liste (kanonisch, ohne Praefix), alphabetisch.
     *
     * @return Modul-Keys der {@code modul:}-Eintraege (nie {@code null})
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
     * Die Titel-Eintraege der Liste — alles, was kein {@code modul:}-Eintrag ist.
     *
     * @return Menue-Titel der Liste in Einfuegereihenfolge (nie {@code null})
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
     * Der Modul-Key eines Listen-Eintrags — oder leer, wenn der Eintrag ein Menue-Titel ist.
     *
     * @param entry Listen-Eintrag, darf {@code null} sein
     * @return kanonischer Modul-Key ohne Praefix, sonst {@code ""}
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
     * Der Listen-Eintrag zu einem Modul-Key: {@code "modul:<key>"}.
     *
     * @param moduleKey Modul-Key, darf {@code null} sein
     * @return Listen-Eintrag, oder {@code ""} bei leerem Key
     * @since 1.608.0
     */
    public static String moduleEntryOf(String moduleKey) {
        String key = canonicalModuleKey(moduleKey);
        return key.isEmpty() ? "" : MODULE_PREFIX + key;
    }

    /**
     * Kanonische Form eines Modul-Keys: getrimmt und kleingeschrieben — dieselbe Normalisierung
     * wie bei den Modul-Rollen ({@code ModuleRoleProperties.canonicalKey}).
     *
     * @param moduleKey roher Key, darf {@code null} sein
     * @return kanonischer Key, nie {@code null}
     * @since 1.608.0
     */
    public static String canonicalModuleKey(String moduleKey) {
        return moduleKey == null ? "" : moduleKey.trim().toLowerCase(Locale.ROOT);
    }
}

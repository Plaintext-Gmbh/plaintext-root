/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

/**
 * Decides how {@link MenuItemImpl#isOn()} answers when a menu item declares no matching role.
 * <p>
 * The two policies differ in their default answer, which is why they cannot be merged into one
 * rule:
 * <ul>
 *   <li>{@link #PERMISSIVE} — a menu item without {@code roles} is visible to everyone. Only an
 *       explicit, non-matching {@code roles} list hides it.</li>
 *   <li>{@link #STRICT} — a menu item is hidden unless the user holds ROLE_ROOT, ROLE_ADMIN
 *       (outside the Root menu), the derived {@code ROLE_MENU_<menuId>}, one of the declared
 *       {@code roles}, or a role matching one of the declared {@code roleStartsWith}
 *       prefixes.</li>
 * </ul>
 * Module and mandate visibility are evaluated under both policies.
 * <p>
 * Configured via {@code plaintext.menu.access-policy}; the default is {@link #PERMISSIVE}, which
 * is the behaviour every existing application relies on.
 * <p>
 * Not to be confused with {@code PageGuardMode.STRICT} in {@code plaintext-root-webapp}: that one
 * decides what happens to a <em>view</em> which no menu item claims, while this one decides
 * whether a <em>menu item</em> is visible in the first place.
 *
 * @since 1.480.0
 */
public enum MenuAccessPolicy {

    /** Visible unless an explicit role list excludes the user. Historic default. */
    PERMISSIVE,

    /** Hidden unless a rule explicitly admits the user. */
    STRICT;

    /**
     * Parses a policy name leniently — unknown or blank values fall back to {@link #PERMISSIVE}
     * rather than failing application startup over a typo in a configuration file.
     *
     * @param value the configured value, may be null or blank
     * @return the parsed policy, never null
     */
    public static MenuAccessPolicy from(String value) {
        if (value == null || value.isBlank()) {
            return PERMISSIVE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PERMISSIVE;
        }
    }
}

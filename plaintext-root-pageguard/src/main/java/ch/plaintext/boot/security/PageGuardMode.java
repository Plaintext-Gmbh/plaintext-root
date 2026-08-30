/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

/**
 * Operating mode of the page access guard (card 308).
 *
 * <p>Regardless of the mode, the following always applies:
 * <ul>
 *   <li>Menu links are compared canonically (the extensions {@code .htm}/{@code .html}/
 *       {@code .xhtml}/{@code .jsf} and a leading slash are ignored on both sides). Previously the
 *       comparison failed for every link that did not end in exactly {@code .html}.</li>
 *   <li>An exception during the check leads to a <b>denial</b> (previously: allow).</li>
 *   <li>Allowlist and view aliases are evaluated.</li>
 * </ul>
 *
 * @author plaintext.ch
 */
public enum PageGuardMode {

    /**
     * Framework default. A view without a menu assignment, without an alias and without an
     * allowlist entry is <b>allowed</b>, but logged with WARN. Parent roles are not inherited.
     *
     * <p>This mode exists so that a consuming app does not abruptly lock out all detail/edit views
     * that do not (yet) have a menu entry after the framework update. The startup report
     * {@code PageAccessGuardStartupReport} lists these views at boot time.
     */
    REPORT,

    /**
     * Fail-closed. A view without a menu assignment, without an alias and without an allowlist
     * entry is <b>denied</b>. In addition, the roles of the parent menu are inherited: a menu item
     * <b>without {@code roles} of its own</b> below a role-restricted parent menu is only
     * reachable if the parent menu is visible as well (just like in the rendered menu, where an
     * invisible parent menu hides all of its children). If a menu item declares its own
     * {@code roles}, those are final — this keeps a deliberately more broadly reachable page below
     * a restricted parent menu possible (e.g. {@code notifications.html}, which the topbar bell
     * links to for every user).
     */
    STRICT
}

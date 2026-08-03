/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark classes as menu items.
 * The annotated class will be automatically discovered and added to the menu.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MenuAnnotation {

    /**
     * The title/label of the menu item.
     *
     * @return the menu title
     */
    String title() default "Dashboard";

    /**
     * The link/URL of the menu item.
     *
     * @return the navigation link
     */
    String link() default "dashboard.html";

    /**
     * The parent menu item (empty for root menu items).
     *
     * @return the parent menu identifier
     */
    String parent() default "";

    /**
     * The order of the menu item (lower numbers appear first).
     *
     * @return the display order
     */
    int order() default 0;

    /**
     * The icon for the menu item (PrimeFaces icon class).
     *
     * @return the icon class name
     */
    String icon() default "";

    /**
     * The roles that can see this menu item (empty means visible to all).
     *
     * @return array of role names
     */
    String[] roles() default {};

    /**
     * Id of the feature module this menu item belongs to (matches
     * {@link ch.plaintext.modules.ModuleDescriptor#moduleId()}), for menu auto-hiding when the
     * module is disabled (Task #016 Phase 2). Empty (default) means the menu item is never
     * auto-hidden by module state — used for the "Module" menu item itself to avoid lock-out.
     *
     * @return the module id, or empty if not tied to a module
     */
    String moduleId() default "";

    /**
     * Stable identifier for this menu item, used to derive the automatic role
     * {@code ROLE_MENU_<menuId>} under the {@code STRICT} access policy.
     * <p>
     * When empty (the default) the id is derived from {@code parent} and {@code title}. Set it
     * explicitly where the title is likely to change: the derived role is persisted against
     * users, so a renamed title would silently revoke their access.
     *
     * @return the stable menu id, or empty to derive it from parent and title
     */
    String menuId() default "";

    /**
     * Role prefixes that grant access to this menu item, for role families that are not known at
     * compile time — a team membership like {@code MEMBER_<team>}, for instance.
     * <p>
     * Evaluated in addition to {@link #roles()}: holding any role that starts with one of these
     * prefixes is enough.
     *
     * @return array of role name prefixes
     */
    String[] roleStartsWith() default {};

}

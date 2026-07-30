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

}

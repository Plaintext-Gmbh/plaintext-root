/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for marking classes as dashboard tiles – the counterpart to
 * {@link ch.plaintext.boot.menu.MenuAnnotation}. The annotated class is discovered automatically
 * at startup and registered as a tile on the home page.
 * <p>
 * Visibility follows the same mechanism as the menus: roles plus the
 * {@link ch.plaintext.MenuVisibilityProvider} (tenant-specific menu visibility). A tile is
 * therefore only visible if the associated menu is active for the tenant.
 *
 * @author plaintext.ch
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DashboardTile {

    /**
     * Unique technical ID of the tile. A {@link DashboardTileDataProvider} uses this ID to enrich
     * the tile with dynamic content.
     *
     * @return the tile ID
     */
    String id() default "";

    /**
     * Title/heading of the tile.
     *
     * @return the title
     */
    String title() default "Dashboard";

    /**
     * Icon of the tile (PrimeFaces icon class, e.g. {@code pi pi-map}).
     *
     * @return the icon class
     */
    String icon() default "";

    /**
     * Optional image URL shown as the tile's header image. External images have to be allowed in
     * the CSP – when in doubt leave it empty and use an icon.
     *
     * @return the image URL
     */
    String image() default "";

    /**
     * Main link of the tile (e.g. {@code bieler-map.html}). If no provider sets explicit actions,
     * a default action is derived from it.
     *
     * @return the navigation link
     */
    String link() default "";

    /**
     * Order of the tile (lower values appear first).
     *
     * @return the sort order
     */
    int order() default 100;

    /**
     * Roles that are allowed to see this tile (empty = visible to everyone).
     *
     * @return array of role names
     */
    String[] roles() default {};

    /**
     * Full menu title against which the tenant-specific visibility is checked
     * (e.g. {@code "Lauftage"} or {@code "Root | Mandate"}). Tile and menu thus share the same
     * visibility rule – without an extra table.
     * <p>
     * <strong>Effectively mandatory:</strong> the value has to match a registered menu title
     * exactly (including the hierarchy, see {@link ch.plaintext.MenuRegistry#getAllMenuTitles()}).
     * Only then is tile visibility traceably coupled to menu visibility: if the menu is hidden for
     * a tenant, the tile disappears along with it.
     * <p>
     * If the value is empty, the check falls back to {@link #title()}; if that title matches no
     * registered menu title, the tile stays visible under the blacklist default (fail-open). Such
     * a mismatch is logged as a WARN at startup by the {@code TileVisibilityValidator}.
     *
     * @return the menu title used for the visibility check
     */
    String menuTitle() default "";
}

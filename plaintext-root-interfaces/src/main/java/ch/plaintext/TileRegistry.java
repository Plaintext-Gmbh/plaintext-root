/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.util.List;

/**
 * Access to all registered dashboard tiles – the counterpart to {@link MenuRegistry}.
 * <p>
 * Returns the {@code @DashboardTile}-annotated classes as {@link TileItem}s, which can be used for
 * configuration or admin pages as well as for assembling the home page.
 *
 * @author plaintext.ch
 */
public interface TileRegistry {

    /**
     * Returns the titles of all registered tiles.
     *
     * @return list of all tile titles
     */
    List<String> getAllTileTitles();

    /**
     * Returns all registered tiles with their metadata.
     *
     * @return list of all tiles
     */
    List<TileItem> getAllTileItems();

    /**
     * A registered dashboard tile with its metadata.
     */
    interface TileItem {

        /** @return the technical ID of the tile. */
        String getId();

        /** @return the title of the tile. */
        String getTitle();

        /** @return the icon class, or an empty string. */
        String getIcon();

        /** @return the image URL, or an empty string. */
        String getImage();

        /** @return the main link, or an empty string. */
        String getLink();

        /** @return the sort order (lower values first). */
        int getOrder();

        /** @return the permitted roles, or an empty list if it is visible to everyone. */
        List<String> getRoles();

        /**
         * @return the menu title against which the tenant-specific visibility is checked.
         */
        String getMenuTitle();

        /**
         * Checks whether the tile is visible to the current user (role-based and tenant-specific
         * visibility combined).
         *
         * @return true if visible
         */
        boolean isOn();
    }
}

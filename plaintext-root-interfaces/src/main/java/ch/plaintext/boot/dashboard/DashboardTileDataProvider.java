/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.DashboardTileData;

/**
 * Bean interface through which a module supplies dynamic content for a dashboard tile (status
 * text, status color, info, actions, dropdown).
 * <p>
 * An implementation is registered as a Spring bean. While the dashboard is assembled, all
 * providers are collected and – via {@link #tileId()} – matched to their respective tile. If no
 * provider exists for a tile, only the static metadata of the {@link DashboardTile} annotation is
 * shown.
 *
 * @author plaintext.ch
 */
public interface DashboardTileDataProvider {

    /**
     * The ID of the tile ({@link DashboardTile#id()}) that this provider enriches.
     *
     * @return the tile ID
     */
    String tileId();

    /**
     * Enriches the given tile with dynamic content. Called once per page request, in the
     * security/tenant context of the current user.
     *
     * @param tile the tile to enrich (its metadata is already set)
     */
    void enrich(DashboardTileData tile);
}

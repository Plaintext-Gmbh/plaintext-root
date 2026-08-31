/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.DashboardTileData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the list of {@link DashboardTileData} to be displayed from all visible, registered
 * tiles – analogous to the {@link ch.plaintext.boot.menu.MenuModelBuilder}.
 * <p>
 * For every tile the static metadata of the {@code @DashboardTile} annotation is taken over and
 * then – if one is present – dynamically enriched through a matching
 * {@link DashboardTileDataProvider}.
 * <p>
 * The tile and provider beans are static (registered at startup time) and are therefore resolved
 * and cached <strong>once</strong>. Per page request only the request-dependent
 * {@link TileItemImpl#isOn()} and {@link DashboardTileDataProvider#enrich} calls are still
 * evaluated.
 *
 * @author plaintext.ch
 */
@Slf4j
public class DashboardTileModelBuilder implements SmartInitializingSingleton {

    @Autowired
    private ApplicationContext applicationContext;

    /** Tiles resolved once and sorted by {@code order} (static at startup time). */
    private volatile List<TileItemImpl> cachedTiles;

    /** Providers resolved once, indexed by tile ID (static at startup time). */
    private volatile Map<String, DashboardTileDataProvider> cachedProviders;

    @Override
    public void afterSingletonsInstantiated() {
        // Resolve the beans once, after the context has fully started up.
        resolveBeans();
    }

    /**
     * Builds the visible dashboard tiles, sorted by {@code order}, enriched via providers.
     *
     * @return list of the tiles to be displayed (never {@code null})
     */
    public List<DashboardTileData> buildTiles() {
        // Fallback (e.g. in a unit test without a container lifecycle): resolve lazily if needed.
        if (cachedTiles == null) {
            resolveBeans();
        }

        if (cachedTiles.isEmpty()) {
            log.debug("Keine Dashboard-Kacheln im Spring-Context gefunden");
            return new ArrayList<>();
        }

        List<DashboardTileData> result = new ArrayList<>();
        for (TileItemImpl item : cachedTiles) {
            // Only visible tiles (roles + tenant-specific visibility) – request-dependent
            if (!item.isOn()) {
                continue;
            }

            DashboardTileData tile = toData(item);

            DashboardTileDataProvider provider = cachedProviders.get(item.getId());
            if (provider != null) {
                try {
                    provider.enrich(tile);
                } catch (Exception e) {
                    // A single faulty tile must not paralyse the whole dashboard
                    log.error("Fehler beim Anreichern der Kachel '{}': {}", item.getId(), e.getMessage(), e);
                }
            }

            // Fallback: if no actions were set but a main link exists
            if (tile.getActions().isEmpty() && item.getLink() != null && !item.getLink().isBlank()) {
                tile.getActions().add(new DashboardTileData.TileAction("Öffnen", item.getLink(), "pi pi-arrow-right"));
            }

            result.add(tile);
        }

        log.debug("Dashboard mit {} sichtbaren Kacheln aufgebaut", result.size());
        return result;
    }

    /**
     * Resolves the static tile and provider beans once and caches them. Idempotent and
     * thread-safe: further calls (e.g. eagerly at startup and lazily from {@link #buildTiles()})
     * are no-ops.
     */
    private synchronized void resolveBeans() {
        if (cachedTiles != null) {
            return;
        }

        List<TileItemImpl> tiles =
            new ArrayList<>(applicationContext.getBeansOfType(TileItemImpl.class).values());
        // The ordering is static -> sort once up front, then only filter per request.
        tiles.sort(Comparator.comparingInt(TileItemImpl::getOrder));

        Map<String, DashboardTileDataProvider> providers = new HashMap<>();
        try {
            for (DashboardTileDataProvider p :
                    applicationContext.getBeansOfType(DashboardTileDataProvider.class).values()) {
                if (p.tileId() != null) {
                    providers.put(p.tileId(), p);
                }
            }
        } catch (Exception e) {
            log.debug("Keine DashboardTileDataProvider verfügbar: {}", e.getMessage());
        }

        cachedProviders = providers;
        cachedTiles = tiles;
        log.debug("Dashboard-Beans aufgelöst: {} Kacheln, {} Provider", tiles.size(), providers.size());
    }

    private DashboardTileData toData(TileItemImpl item) {
        DashboardTileData tile = new DashboardTileData();
        tile.setId(item.getId());
        tile.setTitle(item.getTitle());
        tile.setIcon(item.getIcon());
        tile.setImage(item.getImage());
        tile.setOrder(item.getOrder());
        return tile;
    }
}

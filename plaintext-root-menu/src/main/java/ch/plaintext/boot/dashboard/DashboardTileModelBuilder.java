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
 * Baut die Liste der anzuzeigenden {@link DashboardTileData} aus allen sichtbaren, registrierten
 * Kacheln auf – analog zum {@link ch.plaintext.boot.menu.MenuModelBuilder}.
 * <p>
 * Pro Kachel werden die statischen Metadaten der {@code @DashboardTile}-Annotation übernommen und
 * anschliessend – sofern vorhanden – über einen passenden {@link DashboardTileDataProvider}
 * dynamisch angereichert.
 * <p>
 * Die Kachel- und Provider-Beans sind statisch (zur Startzeit registriert) und werden daher
 * <strong>einmalig</strong> aufgelöst und gecacht. Pro Seitenaufruf werden nur noch die
 * request-abhängigen {@link TileItemImpl#isOn()}- und {@link DashboardTileDataProvider#enrich}-
 * Aufrufe ausgewertet.
 *
 * @author plaintext.ch
 */
@Slf4j
public class DashboardTileModelBuilder implements SmartInitializingSingleton {

    @Autowired
    private ApplicationContext applicationContext;

    /** Einmalig aufgelöste, nach {@code order} sortierte Kacheln (statisch zur Startzeit). */
    private volatile List<TileItemImpl> cachedTiles;

    /** Einmalig aufgelöste Provider, nach Kachel-ID indexiert (statisch zur Startzeit). */
    private volatile Map<String, DashboardTileDataProvider> cachedProviders;

    @Override
    public void afterSingletonsInstantiated() {
        // Beans einmalig nach dem vollständigen Hochfahren des Contexts auflösen.
        resolveBeans();
    }

    /**
     * Baut die sichtbaren Dashboard-Kacheln, sortiert nach {@code order}, angereichert via Provider.
     *
     * @return Liste der anzuzeigenden Kacheln (nie {@code null})
     */
    public List<DashboardTileData> buildTiles() {
        // Fallback (z. B. im Unit-Test ohne Container-Lifecycle): bei Bedarf lazy auflösen.
        if (cachedTiles == null) {
            resolveBeans();
        }

        if (cachedTiles.isEmpty()) {
            log.debug("Keine Dashboard-Kacheln im Spring-Context gefunden");
            return new ArrayList<>();
        }

        List<DashboardTileData> result = new ArrayList<>();
        for (TileItemImpl item : cachedTiles) {
            // Nur sichtbare Kacheln (Rollen + mandatsspezifische Sichtbarkeit) – request-abhängig
            if (!item.isOn()) {
                continue;
            }

            DashboardTileData tile = toData(item);

            DashboardTileDataProvider provider = cachedProviders.get(item.getId());
            if (provider != null) {
                try {
                    provider.enrich(tile);
                } catch (Exception e) {
                    // Eine fehlerhafte Kachel darf das gesamte Dashboard nicht lahmlegen
                    log.error("Fehler beim Anreichern der Kachel '{}': {}", item.getId(), e.getMessage(), e);
                }
            }

            // Fallback: Wenn keine Aktionen gesetzt wurden, aber ein Haupt-Link existiert
            if (tile.getActions().isEmpty() && item.getLink() != null && !item.getLink().isBlank()) {
                tile.getActions().add(new DashboardTileData.TileAction("Öffnen", item.getLink(), "pi pi-arrow-right"));
            }

            result.add(tile);
        }

        log.debug("Dashboard mit {} sichtbaren Kacheln aufgebaut", result.size());
        return result;
    }

    /**
     * Löst die statischen Kachel- und Provider-Beans einmalig auf und cacht sie. Idempotent und
     * thread-safe: weitere Aufrufe (z. B. eager beim Start und lazy aus {@link #buildTiles()}) sind
     * No-ops.
     */
    private synchronized void resolveBeans() {
        if (cachedTiles != null) {
            return;
        }

        List<TileItemImpl> tiles =
            new ArrayList<>(applicationContext.getBeansOfType(TileItemImpl.class).values());
        // Sortierung ist statisch -> einmalig vorsortieren, pro Request nur noch filtern.
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

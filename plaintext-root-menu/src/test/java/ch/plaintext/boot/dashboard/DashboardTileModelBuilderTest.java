/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.DashboardTileData;
import ch.plaintext.MenuVisibilityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardTileModelBuilderTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private MenuVisibilityProvider menuVisibilityProvider;

    private DashboardTileModelBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new DashboardTileModelBuilder();
        ReflectionTestUtils.setField(builder, "applicationContext", applicationContext);
    }

    private TileItemImpl tile(String id, String title, int order, String link) {
        TileItemImpl t = new TileItemImpl();
        t.setId(id);
        t.setTitle(title);
        t.setOrder(order);
        t.setLink(link);
        return t;
    }

    @Test
    void shouldHideTileWhenCoupledMenuTitleIsHidden() {
        // Kopplung: Kachel mit explizitem menuTitle -> Provider blendet exakt diesen Titel aus
        TileItemImpl coupled = tile("biel", "Lauftage", 10, "bieler-map.html");
        coupled.setMenuTitle("Lauftage");
        coupled.setMenuVisibilityProvider(menuVisibilityProvider);
        when(menuVisibilityProvider.isMenuVisible("Lauftage")).thenReturn(false);

        when(applicationContext.getBeansOfType(TileItemImpl.class)).thenReturn(Map.of("t1", coupled));
        when(applicationContext.getBeansOfType(DashboardTileDataProvider.class)).thenReturn(Map.of());

        assertTrue(builder.buildTiles().isEmpty(),
            "Kachel muss ausgeblendet werden, wenn ihr gekoppelter Menü-Titel ausgeblendet ist");
    }

    @Test
    void shouldResolveBeansOnlyOnceAcrossMultipleBuilds() {
        TileItemImpl t = tile("x", "X", 1, "ziel.html");
        when(applicationContext.getBeansOfType(TileItemImpl.class)).thenReturn(Map.of("t1", t));
        when(applicationContext.getBeansOfType(DashboardTileDataProvider.class)).thenReturn(Map.of());

        builder.buildTiles();
        builder.buildTiles();
        builder.buildTiles();

        // Statische Bean-Mengen werden gecacht -> nur ein einziger getBeansOfType-Aufruf je Typ
        verify(applicationContext, times(1)).getBeansOfType(TileItemImpl.class);
        verify(applicationContext, times(1)).getBeansOfType(DashboardTileDataProvider.class);
    }

    @Test
    void afterSingletonsInstantiatedShouldPrewarmCache() {
        TileItemImpl t = tile("x", "X", 1, "ziel.html");
        when(applicationContext.getBeansOfType(TileItemImpl.class)).thenReturn(Map.of("t1", t));
        when(applicationContext.getBeansOfType(DashboardTileDataProvider.class)).thenReturn(Map.of());

        builder.afterSingletonsInstantiated();
        List<DashboardTileData> result = builder.buildTiles();

        assertEquals(1, result.size());
        // Cache wurde beim Start gefüllt -> buildTiles löst nicht erneut auf
        verify(applicationContext, times(1)).getBeansOfType(TileItemImpl.class);
    }

    @Test
    void shouldReturnEmptyWhenNoTiles() {
        when(applicationContext.getBeansOfType(TileItemImpl.class)).thenReturn(Map.of());
        assertTrue(builder.buildTiles().isEmpty());
    }

    @Test
    void shouldSortByOrderAndFilterHiddenTiles() {
        TileItemImpl visibleB = tile("b", "B", 20, null);
        TileItemImpl visibleA = tile("a", "A", 10, null);

        TileItemImpl hidden = tile("h", "Hidden", 5, null);
        hidden.setMenuVisibilityProvider(menuVisibilityProvider);
        when(menuVisibilityProvider.isMenuVisible("Hidden")).thenReturn(false);

        Map<String, TileItemImpl> beans = new LinkedHashMap<>();
        beans.put("t1", visibleB);
        beans.put("t2", visibleA);
        beans.put("t3", hidden);

        when(applicationContext.getBeansOfType(TileItemImpl.class)).thenReturn(beans);
        when(applicationContext.getBeansOfType(DashboardTileDataProvider.class)).thenReturn(Map.of());

        List<DashboardTileData> result = builder.buildTiles();

        assertEquals(2, result.size(), "Versteckte Kachel muss herausgefiltert werden");
        assertEquals("A", result.get(0).getTitle());
        assertEquals("B", result.get(1).getTitle());
    }

    @Test
    void shouldAddDefaultActionFromLinkWhenNoProvider() {
        TileItemImpl t = tile("x", "X", 1, "ziel.html");
        when(applicationContext.getBeansOfType(TileItemImpl.class)).thenReturn(Map.of("t1", t));
        when(applicationContext.getBeansOfType(DashboardTileDataProvider.class)).thenReturn(Map.of());

        List<DashboardTileData> result = builder.buildTiles();

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getActions().size());
        assertEquals("ziel.html", result.get(0).getActions().get(0).getLink());
    }

    @Test
    void shouldEnrichTileViaMatchingProvider() {
        TileItemImpl t = tile("biel", "Lauftage", 1, "bieler-map.html");
        when(applicationContext.getBeansOfType(TileItemImpl.class)).thenReturn(Map.of("t1", t));

        DashboardTileDataProvider provider = new DashboardTileDataProvider() {
            @Override
            public String tileId() {
                return "biel";
            }

            @Override
            public void enrich(DashboardTileData tile) {
                tile.setStatusText("Aktiver Lauf: Biel");
                tile.getActions().add(new DashboardTileData.TileAction("Zur Karte", "bieler-map.html"));
            }
        };
        when(applicationContext.getBeansOfType(DashboardTileDataProvider.class))
            .thenReturn(Map.of("p1", provider));

        List<DashboardTileData> result = builder.buildTiles();

        assertEquals(1, result.size());
        assertEquals("Aktiver Lauf: Biel", result.get(0).getStatusText());
        // Provider hat bereits eine Aktion gesetzt -> kein zusätzlicher Default
        assertEquals(1, result.get(0).getActions().size());
        assertEquals("Zur Karte", result.get(0).getActions().get(0).getLabel());
    }

    @Test
    void shouldNotFailWhenProviderThrows() {
        TileItemImpl t = tile("boom", "Boom", 1, null);
        when(applicationContext.getBeansOfType(TileItemImpl.class)).thenReturn(Map.of("t1", t));

        DashboardTileDataProvider provider = new DashboardTileDataProvider() {
            @Override
            public String tileId() {
                return "boom";
            }

            @Override
            public void enrich(DashboardTileData tile) {
                throw new RuntimeException("absichtlicher Fehler");
            }
        };
        when(applicationContext.getBeansOfType(DashboardTileDataProvider.class))
            .thenReturn(Map.of("p1", provider));

        List<DashboardTileData> result = builder.buildTiles();

        assertEquals(1, result.size(), "Fehlerhafter Provider darf das Dashboard nicht abbrechen");
    }
}

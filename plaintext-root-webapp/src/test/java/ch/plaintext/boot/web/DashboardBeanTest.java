/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.DashboardTileData;
import ch.plaintext.boot.dashboard.DashboardTileModelBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sichert die defensive {@code init()}-Logik von {@link DashboardBean} ab: ein fehlerhafter
 * Kachel-Builder darf die Framework-Startseite NICHT mit einem Render-500 abschiessen — bei einer
 * Exception bleibt {@code tiles} eine leere (nicht-{@code null}) Liste und es fliegt kein Throw.
 */
class DashboardBeanTest {

    private DashboardBean beanWith(DashboardTileModelBuilder builder) {
        DashboardBean bean = new DashboardBean();
        ReflectionTestUtils.setField(bean, "dashboardTileModelBuilder", builder);
        return bean;
    }

    @Test
    void init_leereKachelliste() {
        DashboardTileModelBuilder builder = mock(DashboardTileModelBuilder.class);
        when(builder.buildTiles()).thenReturn(List.of());

        DashboardBean bean = beanWith(builder);
        bean.init();

        assertNotNull(bean.getTiles());
        assertTrue(bean.getTiles().isEmpty());
    }

    @Test
    void init_befuellteKachelliste() {
        DashboardTileModelBuilder builder = mock(DashboardTileModelBuilder.class);
        DashboardTileData tile = mock(DashboardTileData.class);
        when(builder.buildTiles()).thenReturn(List.of(tile));

        DashboardBean bean = beanWith(builder);
        bean.init();

        assertEquals(1, bean.getTiles().size());
    }

    @Test
    void init_builderWirftException_liefertLeereListeOhneThrow() {
        DashboardTileModelBuilder builder = mock(DashboardTileModelBuilder.class);
        when(builder.buildTiles()).thenThrow(new RuntimeException("Kachel kaputt"));

        DashboardBean bean = beanWith(builder);

        // Defensive: kein Render-500 — init() schluckt die Exception und liefert eine leere Liste.
        assertDoesNotThrow(bean::init);
        assertNotNull(bean.getTiles());
        assertTrue(bean.getTiles().isEmpty());
    }
}

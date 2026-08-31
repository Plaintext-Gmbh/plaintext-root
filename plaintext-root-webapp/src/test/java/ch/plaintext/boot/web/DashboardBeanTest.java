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
 * Safeguards the defensive {@code init()} logic of {@link DashboardBean}: a faulty
 * tile builder must NOT shoot down the framework start page with a render 500 — on an
 * exception {@code tiles} stays an empty (non-{@code null}) list and nothing is thrown.
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

        // Defensive: no render 500 — init() swallows the exception and returns an empty list.
        assertDoesNotThrow(bean::init);
        assertNotNull(bean.getTiles());
        assertTrue(bean.getTiles().isEmpty());
    }
}

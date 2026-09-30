/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.DashboardTileData;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.dashboard.DashboardTileModelBuilder;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreferencesBackingBean;
import ch.plaintext.boot.startseite.StartseitenLayout;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardBeanTest {

    private final UserPreferencesBackingBean prefs = mock(UserPreferencesBackingBean.class);

    private DashboardBean beanWith(DashboardTileModelBuilder builder) {
        DashboardBean bean = new DashboardBean();
        ReflectionTestUtils.setField(bean, "dashboardTileModelBuilder", builder);
        ReflectionTestUtils.setField(bean, "userPreferences", prefs);
        PlaintextSecurity security = mock(PlaintextSecurity.class);
        when(security.getMandat()).thenReturn("plaintext");
        ReflectionTestUtils.setField(bean, "plaintextSecurity", security);
        return bean;
    }

    private static DashboardTileData kachel(String id) {
        DashboardTileData t = new DashboardTileData();
        t.setId(id);
        return t;
    }

    /** Builder, der bei jedem Aufruf frische Kacheln a, b, c liefert (wie der echte). */
    private static DashboardTileModelBuilder builderMit(String... ids) {
        DashboardTileModelBuilder builder = mock(DashboardTileModelBuilder.class);
        when(builder.buildTiles(any())).thenAnswer(inv -> {
            List<DashboardTileData> liste = new ArrayList<>();
            for (String id : ids) {
                liste.add(kachel(id));
            }
            return liste;
        });
        return builder;
    }

    private static List<String> ids(List<DashboardTileData> kacheln) {
        return kacheln.stream().map(DashboardTileData::getId).toList();
    }

    @Test
    void init_leereKachelliste() {
        DashboardBean bean = beanWith(builderMit());
        bean.init();

        assertNotNull(bean.getTiles());
        assertTrue(bean.getTiles().isEmpty());
        assertFalse(bean.isKachelnVorhanden());
    }

    @Test
    void init_befuellteKachelliste() {
        DashboardBean bean = beanWith(builderMit("a"));
        bean.init();

        assertEquals(1, bean.getTiles().size());
        assertTrue(bean.isKachelnVorhanden());
    }

    @Test
    void init_builderWirftException_liefertLeereListeOhneThrow() {
        DashboardTileModelBuilder builder = mock(DashboardTileModelBuilder.class);
        when(builder.buildTiles(any())).thenThrow(new RuntimeException("Kachel kaputt"));

        DashboardBean bean = beanWith(builder);

        // Defensive: no render 500 — init() swallows the exception and returns an empty list.
        assertDoesNotThrow(bean::init);
        assertNotNull(bean.getTiles());
        assertTrue(bean.getTiles().isEmpty());
    }

    /** Karte 1351: das gespeicherte Layout des Mandanten ordnet und blendet aus. */
    @Test
    @SuppressWarnings("unchecked")
    void init_wendetGespeichertesLayoutAn_undReichertAusgeblendeteNichtAn() {
        when(prefs.startseite("plaintext")).thenReturn(new StartseitenLayout(List.of(
                new StartseitenLayout.Eintrag("c", true, false),
                new StartseitenLayout.Eintrag("a", false, true))));
        DashboardTileModelBuilder builder = builderMit("a", "b", "c");
        DashboardBean bean = beanWith(builder);

        bean.init();

        assertEquals(List.of("c", "b"), ids(bean.getTiles()), "a ist ausgeblendet, b neu hinten");
        assertFalse(bean.getTiles().get(0).isHalfWidth());

        ArgumentCaptor<Predicate<String>> anreichern = ArgumentCaptor.forClass(Predicate.class);
        verify(builder).buildTiles(anreichern.capture());
        assertFalse(anreichern.getValue().test("a"), "ausgeblendete Kachel wird nicht angereichert");
        assertTrue(anreichern.getValue().test("c"));
    }

    @Test
    void anpassen_zeigtAuchAusgeblendete_undFuelltDasFeld() {
        when(prefs.startseite("plaintext")).thenReturn(new StartseitenLayout(List.of(
                new StartseitenLayout.Eintrag("b", false, true))));
        DashboardBean bean = beanWith(builderMit("a", "b"));
        bean.init();
        assertEquals(List.of("a"), ids(bean.getTiles()));

        bean.anpassen();

        assertTrue(bean.isBearbeiten());
        assertEquals(List.of("b", "a"), ids(bean.getTiles()));
        assertTrue(bean.getTiles().get(0).isHidden());
        assertEquals("b:0:1,a:1:1", bean.getAnordnung());
    }

    @Test
    void speichern_merktDasLayoutDesMandanten_undVerwirftFremdeIds() {
        DashboardBean bean = beanWith(builderMit("a", "b"));
        bean.init();
        bean.anpassen();

        bean.setAnordnung("geheim:1:1,b:1:0,a:0:1");
        bean.speichern();

        ArgumentCaptor<StartseitenLayout> gespeichert = ArgumentCaptor.forClass(StartseitenLayout.class);
        verify(prefs).merkeStartseite(eq("plaintext"), gespeichert.capture());
        assertEquals(List.of(
                new StartseitenLayout.Eintrag("b", true, false),
                new StartseitenLayout.Eintrag("a", false, true)), gespeichert.getValue().getEintraege());
        assertFalse(bean.isBearbeiten());
    }

    @Test
    void speichern_ohneFeldwert_speichertNichts() {
        DashboardBean bean = beanWith(builderMit("a"));
        bean.init();
        bean.anpassen();
        bean.setAnordnung("");

        bean.speichern();

        verify(prefs, never()).merkeStartseite(any(), any());
        assertFalse(bean.isBearbeiten());
    }

    @Test
    void abbrechen_speichertNichts() {
        DashboardBean bean = beanWith(builderMit("a"));
        bean.init();
        bean.anpassen();
        bean.setAnordnung("a:0:0");

        bean.abbrechen();

        verify(prefs, never()).merkeStartseite(any(), any());
        assertFalse(bean.isBearbeiten());
        assertEquals(List.of("a"), ids(bean.getTiles()));
    }

    @Test
    void standardWiederherstellen_entferntDasLayout() {
        DashboardBean bean = beanWith(builderMit("a", "b"));
        bean.init();
        bean.anpassen();

        bean.standardWiederherstellen();

        verify(prefs).merkeStartseite(eq("plaintext"), isNull());
        assertTrue(bean.isBearbeiten(), "bleibt im Edit-Modus");
        assertEquals("a:1:1,b:1:1", bean.getAnordnung());
    }

    @Test
    void ohneMandant_leererSchluessel() {
        DashboardBean bean = new DashboardBean();
        assertEquals("", bean.schluessel());
        assertNull(ReflectionTestUtils.getField(bean, "plaintextSecurity"));
    }
}

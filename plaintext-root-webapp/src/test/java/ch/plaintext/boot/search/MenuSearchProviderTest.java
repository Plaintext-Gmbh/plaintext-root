/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import ch.plaintext.MenuRegistry;
import ch.plaintext.boot.search.SearchProvider.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MenuSearchProviderTest {

    private static MenuRegistry.MenuItem item(String title, String parent, String link, boolean on) {
        MenuRegistry.MenuItem i = mock(MenuRegistry.MenuItem.class);
        when(i.getTitle()).thenReturn(title);
        when(i.getParent()).thenReturn(parent);
        when(i.getLink()).thenReturn(link);
        when(i.getIcon()).thenReturn("pi pi-home");
        when(i.isOn()).thenReturn(on);
        return i;
    }

    @Test
    void istNichtMenuScopedUndSelbstAbgesichert() {
        MenuSearchProvider p = new MenuSearchProvider(mock(MenuRegistry.class));
        assertFalse(p.isMenuScoped());
        assertEquals("menu", p.providerId());
        assertEquals("Navigation", p.moduleTitle());
    }

    @Test
    void registryFehlerLiefertLeer() {
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenThrow(new RuntimeException("kaputt"));
        MenuSearchProvider p = new MenuSearchProvider(registry);
        assertTrue(p.search("kontakte", 10).isEmpty());
    }

    @Test
    void nullItemListeLiefertLeer() {
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(null);
        MenuSearchProvider p = new MenuSearchProvider(registry);
        assertTrue(p.search("kontakte", 10).isEmpty());
    }

    @Test
    void exakterTitelBekommtHoechstenScore() {
        MenuRegistry.MenuItem exakt = item("Kontakte", "Stammdaten", "kontakte.html", true);
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(List.of(exakt));

        MenuSearchProvider p = new MenuSearchProvider(registry);
        List<SearchHit> hits = p.search("kontakte", 10);
        assertEquals(1, hits.size());
        assertEquals(100, hits.get(0).getScore(), "exakter Titel-Treffer => 100");
    }

    @Test
    void trefferNurUeberParentBekommtNiedrigenScore() {
        // Titel enthaelt "fak" NICHT, aber der Parent schon.
        MenuRegistry.MenuItem rechnung = item("Ausgangsrechnung", "Fakturierung", "rechnung.html", true);
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(List.of(rechnung));

        MenuSearchProvider p = new MenuSearchProvider(registry);
        List<SearchHit> hits = p.search("fakt", 10);
        assertEquals(1, hits.size());
        assertEquals(30, hits.get(0).getScore(), "reiner Parent-Treffer => 30");
    }

    @Test
    void mehrereTeileMatchenUeberParentUndTitel() {
        // Multi-Token ("Teile"): jedes Teil muss irgendwo im Pfad "Parent + Titel" vorkommen.
        MenuRegistry.MenuItem settings = item("Settings", "Root", "settings.html", true);
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(List.of(settings));
        MenuSearchProvider p = new MenuSearchProvider(registry);

        assertEquals(1, p.search("roo sett", 10).size(), "'roo'(Root)+'sett'(Settings) => Treffer");
        assertEquals(1, p.search("roo ett", 10).size(), "'roo'+'ett' (Teilstrings) => Treffer");
        assertEquals(1, p.search("sett", 10).size(), "einzelnes Teil 'sett' matcht Settings");
        assertTrue(p.search("roo xyz", 10).isEmpty(), "fehlendes Teil 'xyz' => kein Treffer");
    }

    @Test
    void iconFallbackWennKeinIcon() {
        MenuRegistry.MenuItem ohneIcon = mock(MenuRegistry.MenuItem.class);
        when(ohneIcon.getTitle()).thenReturn("Kontakte");
        when(ohneIcon.getParent()).thenReturn("Stammdaten");
        when(ohneIcon.getLink()).thenReturn("kontakte.html");
        when(ohneIcon.getIcon()).thenReturn(null);
        when(ohneIcon.isOn()).thenReturn(true);
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(List.of(ohneIcon));

        MenuSearchProvider p = new MenuSearchProvider(registry);
        List<SearchHit> hits = p.search("kontakte", 10);
        assertEquals(1, hits.size());
        assertEquals("pi pi-compass", hits.get(0).getIcon());
    }

    @Test
    void isOnFehlerFuehrtZuFailClosed() {
        MenuRegistry.MenuItem boom = mock(MenuRegistry.MenuItem.class);
        when(boom.getTitle()).thenReturn("Kontakte");
        when(boom.getParent()).thenReturn("Stammdaten");
        when(boom.getLink()).thenReturn("kontakte.html");
        when(boom.getIcon()).thenReturn("pi pi-home");
        when(boom.isOn()).thenThrow(new RuntimeException("kaputt"));
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(List.of(boom));

        MenuSearchProvider p = new MenuSearchProvider(registry);
        assertTrue(p.search("kontakte", 10).isEmpty(), "fail-closed: bei Fehler nicht anzeigen");
    }

    @Test
    void groberDeckelVorDemSortieren() {
        // limit*3 als grober Deckel: mehr passende Items als 3*limit → Schleife bricht ab.
        java.util.List<MenuRegistry.MenuItem> viele = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            viele.add(item("Kontakte " + i, "Stammdaten", "kontakte" + i + ".html", true));
        }
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(viele);

        MenuSearchProvider p = new MenuSearchProvider(registry);
        List<SearchHit> hits = p.search("kontakte", 5);
        // Deckel = limit*3 = 15.
        assertEquals(15, hits.size());
    }

    @Test
    void findetSichtbareSeitePerTitel() {
        MenuRegistry.MenuItem kontakte = item("Kontakte", "Stammdaten", "kontakte.html", true);
        MenuRegistry.MenuItem rechnungen = item("Rechnungen", "Fakturierung", "rechnungen.html", true);
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(List.of(kontakte, rechnungen));

        MenuSearchProvider p = new MenuSearchProvider(registry);
        List<SearchHit> hits = p.search("kontak", 10);

        assertEquals(1, hits.size());
        assertEquals("Kontakte", hits.get(0).getTitle());
        assertEquals("kontakte.html", hits.get(0).getLink());
    }

    @Test
    void unsichtbareSeitenWerdenNichtGeliefert() {
        MenuRegistry.MenuItem geheim = item("Geheim", "X", "geheim.html", false);
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(List.of(geheim));

        MenuSearchProvider p = new MenuSearchProvider(registry);
        assertTrue(p.search("geheim", 10).isEmpty());
    }

    @Test
    void seitenOhneLinkWerdenUebersprungen() {
        MenuRegistry.MenuItem ordner = item("Ordner", "", "", true);
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(List.of(ordner));

        MenuSearchProvider p = new MenuSearchProvider(registry);
        assertTrue(p.search("ordner", 10).isEmpty());
    }

    @Test
    void praefixSchlaegtTeiltreffer() {
        MenuRegistry.MenuItem rechnungen = item("Rechnungen", "", "rechnungen.html", true);      // "rech" als Präfix
        MenuRegistry.MenuItem vorrechnungen = item("Vorrechnungen", "", "vorrechnungen.html", true); // "rech" nur als Teil
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(List.of(rechnungen, vorrechnungen));

        MenuSearchProvider p = new MenuSearchProvider(registry);
        List<SearchHit> hits = p.search("rech", 10);
        assertEquals(2, hits.size());
        assertTrue(hits.get(0).getScore() > hits.get(1).getScore()
                || hits.stream().anyMatch(h -> h.getTitle().equals("Rechnungen") && h.getScore() == 80));
    }
}

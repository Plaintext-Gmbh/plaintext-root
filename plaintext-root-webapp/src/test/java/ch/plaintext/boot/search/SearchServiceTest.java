/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import ch.plaintext.MenuRegistry;
import ch.plaintext.boot.search.SearchProvider.SearchHit;
import ch.plaintext.boot.search.SearchService.SearchResultGroup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchService}: grouping, visibility filter, empty/short query,
 * provider error → empty, score sorting/cap and the {@code isMenuScoped} exception.
 */
class SearchServiceTest {

    // ── Test fixtures ────────────────────────────────────────────────────────

    private static SearchHit hit(String title, int score) {
        return new SearchHitDTO(title, "sub", title + ".html", "pi pi-star", score);
    }

    /** Simple provider that returns a fixed list of hits. */
    private static SearchProvider provider(String id, String moduleTitle, boolean menuScoped, List<SearchHit> hits) {
        return new SearchProvider() {
            @Override public String providerId() { return id; }
            @Override public String moduleTitle() { return moduleTitle; }
            @Override public boolean isMenuScoped() { return menuScoped; }
            @Override public List<SearchHit> search(String query, int limit) { return hits; }
        };
    }

    private static MenuRegistry menuWith(String... visibleTitles) {
        MenuRegistry registry = mock(MenuRegistry.class);
        List<MenuRegistry.MenuItem> items = new ArrayList<>();
        for (String t : visibleTitles) {
            items.add(menuItem(t, true));
        }
        when(registry.getAllMenuItems()).thenReturn(items);
        return registry;
    }

    private static MenuRegistry.MenuItem menuItem(String title, boolean on) {
        MenuRegistry.MenuItem item = mock(MenuRegistry.MenuItem.class);
        when(item.getTitle()).thenReturn(title);
        when(item.getFullTitle()).thenReturn(title);
        when(item.isOn()).thenReturn(on);
        return item;
    }

    // ── Query validation ─────────────────────────────────────────────────────

    @Test
    void nullQueryLiefertLeer() {
        SearchService svc = new SearchService(List.of(), menuWith());
        assertTrue(svc.search(null).isEmpty());
    }

    @Test
    void zuKurzeQueryLiefertLeer() {
        SearchProvider p = provider("m", "Modul", true, List.of(hit("Treffer", 10)));
        SearchService svc = new SearchService(List.of(p), menuWith("Modul"));
        // 1 character < MIN_QUERY_LENGTH
        assertTrue(svc.search("a").isEmpty());
        // a trimmed blank does not count
        assertTrue(svc.search("  x  ").isEmpty(), "1 Zeichen nach Trim ist zu kurz");
    }

    // ── Visibility filter (menu coupling) ────────────────────────────────────

    @Test
    void menuScopedProviderNurBeiSichtbaremMenu() {
        SearchProvider sichtbar = provider("a", "Korrespondenz", true, List.of(hit("Brief", 10)));
        SearchProvider unsichtbar = provider("b", "Geheim", true, List.of(hit("Verborgen", 10)));

        SearchService svc = new SearchService(List.of(sichtbar, unsichtbar), menuWith("Korrespondenz"));
        List<SearchResultGroup> groups = svc.search("xx");

        assertEquals(1, groups.size(), "Nur der sichtbare Provider liefert eine Gruppe");
        assertEquals("Korrespondenz", groups.get(0).module());
    }

    @Test
    void vollTitelMenuMatchtLetztesSegment() {
        // The menu is registered as "Root | Mandate"; the provider carries the title "Mandate".
        SearchProvider p = provider("m", "Mandate", true, List.of(hit("Mandat X", 5)));
        MenuRegistry registry = mock(MenuRegistry.class);
        MenuRegistry.MenuItem item = mock(MenuRegistry.MenuItem.class);
        when(item.getTitle()).thenReturn("Mandate");
        when(item.getFullTitle()).thenReturn("Root | Mandate");
        when(item.isOn()).thenReturn(true);
        when(registry.getAllMenuItems()).thenReturn(List.of(item));

        SearchService svc = new SearchService(List.of(p), registry);
        assertEquals(1, svc.search("ma").size());
    }

    @Test
    void nichtMenuScopedProviderUmgehtSichtbarkeitsFilter() {
        // moduleTitle "Navigation" is NOT in the menu list - visible nevertheless, because isMenuScoped=false.
        SearchProvider crossCutting = provider("menu", "Navigation", false, List.of(hit("Seite", 10)));
        SearchService svc = new SearchService(List.of(crossCutting), menuWith("Korrespondenz"));

        List<SearchResultGroup> groups = svc.search("se");
        assertEquals(1, groups.size());
        assertEquals("Navigation", groups.get(0).module());
    }

    // ── Grouping, sorting, cap ───────────────────────────────────────────────

    @Test
    void trefferWerdenNachModulGruppiert() {
        SearchProvider a = provider("a", "Modul A", true, List.of(hit("A1", 10), hit("A2", 20)));
        SearchProvider b = provider("b", "Modul B", true, List.of(hit("B1", 5)));
        SearchService svc = new SearchService(List.of(a, b), menuWith("Modul A", "Modul B"));

        List<SearchResultGroup> groups = svc.search("xx");
        assertEquals(2, groups.size());
        SearchResultGroup ga = groups.stream().filter(g -> g.module().equals("Modul A")).findFirst().orElseThrow();
        assertEquals(2, ga.hits().size());
        // sorted by score in descending order: A2 (20) before A1 (10)
        assertEquals("A2", ga.hits().get(0).getTitle());
    }

    @Test
    void trefferProModulWerdenGecappt() {
        List<SearchHit> viele = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            viele.add(hit("T" + i, i));
        }
        SearchProvider p = provider("m", "Modul", true, viele);
        SearchService svc = new SearchService(List.of(p), menuWith("Modul"));

        SearchResultGroup g = svc.search("tt").get(0);
        assertEquals(SearchService.MAX_HITS_PER_MODULE, g.hits().size());
        // highest score first
        assertEquals("T19", g.hits().get(0).getTitle());
    }

    // ── Robustness ───────────────────────────────────────────────────────────

    @Test
    void fehlerhafterProviderLiefertLeerUndBlockiertNicht() {
        SearchProvider kaputt = new SearchProvider() {
            @Override public String providerId() { return "boom"; }
            @Override public String moduleTitle() { return "Kaputt"; }
            @Override public List<SearchHit> search(String query, int limit) {
                throw new RuntimeException("Absicht");
            }
        };
        SearchProvider ok = provider("ok", "Heil", true, List.of(hit("Gut", 10)));

        SearchService svc = new SearchService(List.of(kaputt, ok), menuWith("Kaputt", "Heil"));
        List<SearchResultGroup> groups = svc.search("gg");

        // The broken provider does not show up, the healthy one does.
        assertEquals(1, groups.size());
        assertEquals("Heil", groups.get(0).module());
    }

    @Test
    void nullTrefferListeWirdToleriert() {
        SearchProvider nullReturner = provider("n", "Modul", true, null);
        SearchService svc = new SearchService(List.of(nullReturner), menuWith("Modul"));
        assertTrue(svc.search("xx").isEmpty());
    }

    @Test
    void trefferOhneLinkWerdenAussortiert() {
        SearchHit ohneLink = new SearchHitDTO("Titel", "sub", "  ", "pi", 5);
        SearchHit mitLink = new SearchHitDTO("Titel2", "sub", "ziel.html", "pi", 5);
        SearchProvider p = provider("m", "Modul", true, List.of(ohneLink, mitLink));

        SearchService svc = new SearchService(List.of(p), menuWith("Modul"));
        SearchResultGroup g = svc.search("ti").get(0);
        assertEquals(1, g.hits().size());
        assertEquals("Titel2", g.hits().get(0).getTitle());
    }

    @Test
    void leereMenuRegistryDeaktiviertFilterNichtDenService() {
        // When no menu titles are known (fail-open), menu-scoped providers are queried nevertheless.
        SearchProvider p = provider("m", "Modul", true, List.of(hit("Treffer", 10)));
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(List.of());

        SearchService svc = new SearchService(List.of(p), registry);
        List<SearchResultGroup> groups = svc.search("tr");
        assertNotNull(groups);
        assertEquals(1, groups.size());
    }

    @Test
    void nullProviderListeWirdToleriert() {
        // The constructor has to cope with a null provider list (→ empty list).
        SearchService svc = new SearchService(null, menuWith("Modul"));
        assertTrue(svc.search("xx").isEmpty());
    }

    @Test
    void vollTitelMatchtWennModulTitelNichtDirektInListe() {
        // The menu is registered only as the full title "Extras | Kalender" (getTitle returns null),
        // the provider module title is "Kalender" → matches via the last segment.
        SearchProvider p = provider("cal", "Kalender", true, List.of(hit("Termin", 10)));
        MenuRegistry registry = mock(MenuRegistry.class);
        MenuRegistry.MenuItem item = mock(MenuRegistry.MenuItem.class);
        when(item.getTitle()).thenReturn(null);
        when(item.getFullTitle()).thenReturn("Extras | Kalender");
        when(item.isOn()).thenReturn(true);
        when(registry.getAllMenuItems()).thenReturn(List.of(item));

        SearchService svc = new SearchService(List.of(p), registry);
        List<SearchResultGroup> groups = svc.search("te");
        assertEquals(1, groups.size());
        assertEquals("Kalender", groups.get(0).module());
    }

    @Test
    void nullMenuItemsDeaktiviertFilter() {
        SearchProvider p = provider("m", "Modul", true, List.of(hit("Treffer", 5)));
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenReturn(null);

        SearchService svc = new SearchService(List.of(p), registry);
        // null items → no visibility information → fail-open, the provider is queried.
        assertEquals(1, svc.search("tr").size());
    }

    @Test
    void fehlerhafteMenuRegistryDeaktiviertFilter() {
        SearchProvider p = provider("m", "Modul", true, List.of(hit("Treffer", 5)));
        MenuRegistry registry = mock(MenuRegistry.class);
        when(registry.getAllMenuItems()).thenThrow(new RuntimeException("menu weg"));

        SearchService svc = new SearchService(List.of(p), registry);
        // registry error → empty title set → fail-open.
        assertEquals(1, svc.search("tr").size());
    }

    @Test
    void isMenuScopedFehlerWirdAlsGekoppeltBehandelt() {
        // isMenuScoped() throws → treated as menu-scoped (true); module invisible → skipped.
        SearchProvider p = new SearchProvider() {
            @Override public String providerId() { return "x"; }
            @Override public String moduleTitle() { return "Unsichtbar"; }
            @Override public boolean isMenuScoped() { throw new RuntimeException("boom"); }
            @Override public List<SearchHit> search(String query, int limit) { return List.of(hit("T", 1)); }
        };
        SearchService svc = new SearchService(List.of(p), menuWith("Sichtbar"));
        assertTrue(svc.search("xx").isEmpty());
    }

    @Test
    void blankModulTitelProviderWirdUebersprungen() {
        SearchProvider blank = provider("b", "   ", true, List.of(hit("T", 1)));
        SearchService svc = new SearchService(List.of(blank), menuWith("Modul"));
        assertTrue(svc.search("xx").isEmpty());
    }

    @Test
    void moduleTitleFehlerProviderWirdUebersprungen() {
        SearchProvider boom = new SearchProvider() {
            @Override public String providerId() { return "x"; }
            @Override public String moduleTitle() { throw new RuntimeException("boom"); }
            @Override public List<SearchHit> search(String query, int limit) { return List.of(hit("T", 1)); }
        };
        SearchService svc = new SearchService(List.of(boom), menuWith("Modul"));
        assertTrue(svc.search("xx").isEmpty());
    }

    @Test
    void providerIdFehlerImDiagnosePfadWirdGekapselt() {
        // Menu-scoped, invisible module → the diagnostic log calls providerId(), which throws here.
        SearchProvider p = new SearchProvider() {
            @Override public String providerId() { throw new RuntimeException("id kaputt"); }
            @Override public String moduleTitle() { return "Unsichtbar"; }
            @Override public List<SearchHit> search(String query, int limit) { return List.of(hit("T", 1)); }
        };
        SearchService svc = new SearchService(List.of(p), menuWith("Sichtbar"));
        // Must not throw, and the provider stays out.
        assertTrue(svc.search("xx").isEmpty());
    }

    @Test
    void langeQueryWirdGedeckeltOhneFehler() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append('a');
        }
        SearchProvider p = provider("m", "Modul", true, List.of(hit("Treffer", 1)));
        SearchService svc = new SearchService(List.of(p), menuWith("Modul"));
        // must not throw
        assertNotNull(svc.search(sb.toString()));
    }
}

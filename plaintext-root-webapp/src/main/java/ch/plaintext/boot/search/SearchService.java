/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import ch.plaintext.MenuRegistry;
import ch.plaintext.boot.search.SearchProvider.SearchHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregiert die globale Suche (Cmd+K) über alle registrierten {@link SearchProvider}-Beans.
 * <p>
 * Spiegelt das {@code DashboardTileModelBuilder}-Muster: Spring sammelt alle Provider-Beans
 * automatisch (Konstruktor-Injektion einer {@code List<SearchProvider>}), root fragt sie ab und
 * gruppiert die Ergebnisse nach {@link SearchProvider#moduleTitle()}.
 * <p>
 * <b>Sichtbarkeits-/Security-Kopplung</b> (wie Tiles/Menüs): ein Provider wird nur abgefragt, wenn
 * sein {@code moduleTitle} in {@link MenuRegistry#getAllMenuTitles()} sichtbar ist. So tauchen keine
 * Treffer aus Modulen auf, die der Benutzer/Mandant gar nicht sieht. Die feinere Mandanten-Filterung
 * je Treffer macht jeder Provider selbst.
 * <p>
 * <b>Robustheit:</b> jeder Provider läuft in try/catch; ein fehlerhafter Provider liefert eine leere
 * Liste statt die Gesamtsuche zu sprengen. Zu kurze/leere Queries liefern ein leeres Ergebnis; die
 * Query-Länge wird gedeckelt.
 *
 * @author plaintext.ch
 */
@Slf4j
@Service
public class SearchService {

    /** Kürzere Queries als das werden ignoriert (leeres Ergebnis). */
    static final int MIN_QUERY_LENGTH = 2;

    /** Query wird auf diese Länge gekürzt (Schutz gegen pathologisch lange Eingaben). */
    static final int MAX_QUERY_LENGTH = 100;

    /** Maximale Trefferzahl je Modul-Gruppe. */
    static final int MAX_HITS_PER_MODULE = 8;

    private final List<SearchProvider> providers;
    private final MenuRegistry menuRegistry;

    /**
     * @param providers    alle im Context registrierten Provider-Beans (von Spring gesammelt; kann
     *                     leer sein, wenn kein Modul einen Provider beisteuert)
     * @param menuRegistry Menü-Registry zur Sichtbarkeits-Kopplung
     */
    public SearchService(List<SearchProvider> providers, MenuRegistry menuRegistry) {
        this.providers = providers != null ? providers : List.of();
        this.menuRegistry = menuRegistry;
        log.debug("SearchService initialisiert mit {} Provider(n)", this.providers.size());
    }

    /**
     * Führt die globale Suche aus: fragt alle sichtbaren Provider ab und gruppiert die Ergebnisse
     * nach Modul-Titel. Die Gruppen erscheinen in der Reihenfolge des ersten Treffers.
     *
     * @param query der Suchbegriff (roh; wird getrimmt und gedeckelt)
     * @return gruppierte Trefferliste (nie {@code null}, ggf. leer)
     */
    public List<SearchResultGroup> search(String query) {
        String q = normalize(query);
        if (q.length() < MIN_QUERY_LENGTH) {
            return List.of();
        }

        Set<String> visibleTitles = safeVisibleMenuTitles();

        // Reihenfolge der Gruppen = Reihenfolge des ersten Treffers → LinkedHashMap
        Map<String, List<SearchHit>> grouped = new LinkedHashMap<>();

        for (SearchProvider provider : providers) {
            String moduleTitle = safeModuleTitle(provider);
            if (moduleTitle != null && isProviderVisible(provider, moduleTitle, visibleTitles)) {
                List<SearchHit> hits = queryProvider(provider, q);
                if (!hits.isEmpty()) {
                    grouped.computeIfAbsent(moduleTitle, k -> new ArrayList<>()).addAll(hits);
                }
            }
        }

        List<SearchResultGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<SearchHit>> e : grouped.entrySet()) {
            List<SearchHit> hits = e.getValue();
            // Innerhalb der Gruppe nach Score absteigend sortieren und cappen.
            hits.sort(Comparator.comparingInt(SearchHit::getScore).reversed());
            List<SearchHit> capped = hits.size() > MAX_HITS_PER_MODULE
                    ? new ArrayList<>(hits.subList(0, MAX_HITS_PER_MODULE))
                    : hits;
            result.add(new SearchResultGroup(e.getKey(), capped));
        }
        return result;
    }

    /**
     * Fragt einen einzelnen Provider ab und fängt jeden Fehler ab (leere Liste bei Problemen).
     */
    private List<SearchHit> queryProvider(SearchProvider provider, String query) {
        try {
            List<SearchHit> hits = provider.search(query, MAX_HITS_PER_MODULE);
            if (hits == null) {
                return List.of();
            }
            // Defensive: null-Treffer und Treffer ohne Link/Titel aussortieren.
            List<SearchHit> clean = new ArrayList<>(hits.size());
            for (SearchHit h : hits) {
                if (h != null && h.getTitle() != null && h.getLink() != null && !h.getLink().isBlank()) {
                    clean.add(h);
                }
            }
            return clean;
        } catch (Exception ex) {
            log.warn("SearchProvider '{}' warf eine Exception – Ergebnis ignoriert: {}",
                    safeProviderId(provider), ex.getMessage());
            return List.of();
        }
    }

    /**
     * Ob ein Provider abgefragt werden darf: quer schneidende Root-Provider immer, menü-gekoppelte
     * Fachmodul-Provider nur bei sichtbarem Modul-Menü.
     */
    private boolean isProviderVisible(SearchProvider provider, String moduleTitle, Set<String> visibleTitles) {
        if (isMenuScoped(provider) && !isModuleVisible(moduleTitle, visibleTitles)) {
            log.debug("Provider '{}' übersprungen: Modul '{}' nicht sichtbar",
                    safeProviderId(provider), moduleTitle);
            return false;
        }
        return true;
    }

    /**
     * Ein Modul gilt als sichtbar, wenn sein Titel als Menü-Titel oder als letztes Segment eines
     * Voll-Titels ({@code "Parent | Titel"}) unter den sichtbaren Menü-Titeln vorkommt. Ist die
     * Menü-Registry leer/nicht verfügbar, wird nicht gefiltert (fail-open auf Sichtbarkeit, die
     * eigentliche Mandanten-Absicherung macht jeder Provider selbst).
     */
    private boolean isModuleVisible(String moduleTitle, Set<String> visibleTitles) {
        if (visibleTitles.isEmpty()) {
            return true;
        }
        if (visibleTitles.contains(moduleTitle)) {
            return true;
        }
        // Voll-Titel "Parent | Titel": auf letztes Segment matchen.
        for (String title : visibleTitles) {
            int idx = title.lastIndexOf('|');
            if (idx >= 0 && title.substring(idx + 1).trim().equals(moduleTitle)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> safeVisibleMenuTitles() {
        try {
            List<MenuRegistry.MenuItem> items = menuRegistry.getAllMenuItems();
            if (items == null) {
                return Set.of();
            }
            Set<String> titles = new HashSet<>();
            for (MenuRegistry.MenuItem item : items) {
                if (item != null && item.isOn()) {
                    if (item.getTitle() != null) {
                        titles.add(item.getTitle());
                    }
                    if (item.getFullTitle() != null) {
                        titles.add(item.getFullTitle());
                    }
                }
            }
            return titles;
        } catch (Exception ex) {
            log.debug("Menü-Sichtbarkeit nicht ermittelbar – Sichtbarkeitsfilter deaktiviert: {}",
                    ex.getMessage());
            return Set.of();
        }
    }

    private static String normalize(String query) {
        if (query == null) {
            return "";
        }
        String q = query.trim();
        return q.length() > MAX_QUERY_LENGTH ? q.substring(0, MAX_QUERY_LENGTH) : q;
    }

    private static boolean isMenuScoped(SearchProvider provider) {
        try {
            return provider.isMenuScoped();
        } catch (Exception _) {
            return true;
        }
    }

    private static String safeModuleTitle(SearchProvider provider) {
        try {
            String t = provider.moduleTitle();
            return (t != null && !t.isBlank()) ? t : null;
        } catch (Exception _) {
            return null;
        }
    }

    private static String safeProviderId(SearchProvider provider) {
        try {
            return provider.providerId();
        } catch (Exception _) {
            return provider.getClass().getSimpleName();
        }
    }

    /**
     * Eine nach Modul gruppierte Trefferliste.
     *
     * @param module das Modul (Gruppentitel)
     * @param hits   die Treffer dieses Moduls (bereits sortiert und gecappt)
     */
    public record SearchResultGroup(String module, List<SearchHit> hits) {
    }
}

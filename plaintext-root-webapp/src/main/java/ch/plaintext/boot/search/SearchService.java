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
 * Aggregates the global search (Cmd+K) across all registered {@link SearchProvider} beans.
 * <p>
 * Mirrors the {@code DashboardTileModelBuilder} pattern: Spring collects all provider beans
 * automatically (constructor injection of a {@code List<SearchProvider>}), root queries them and
 * groups the results by {@link SearchProvider#moduleTitle()}.
 * <p>
 * <b>Visibility/security coupling</b> (like tiles/menus): a provider is only queried when
 * its {@code moduleTitle} is visible in {@link MenuRegistry#getAllMenuTitles()}. This way no
 * hits show up from modules that the user/tenant does not see at all. The finer per-hit tenant
 * filtering is done by each provider itself.
 * <p>
 * <b>Robustness:</b> every provider runs inside try/catch; a faulty provider returns an empty
 * list instead of blowing up the whole search. Queries that are too short/empty return an empty
 * result; the query length is capped.
 *
 * @author plaintext.ch
 */
@Slf4j
@Service
public class SearchService {

    /** Queries shorter than this are ignored (empty result). */
    static final int MIN_QUERY_LENGTH = 2;

    /** The query is truncated to this length (protection against pathologically long inputs). */
    static final int MAX_QUERY_LENGTH = 100;

    /** Maximum number of hits per module group. */
    static final int MAX_HITS_PER_MODULE = 8;

    private final List<SearchProvider> providers;
    private final MenuRegistry menuRegistry;

    /**
     * @param providers    all provider beans registered in the context (collected by Spring; may be
     *                     empty when no module contributes a provider)
     * @param menuRegistry menu registry for the visibility coupling
     */
    public SearchService(List<SearchProvider> providers, MenuRegistry menuRegistry) {
        this.providers = providers != null ? providers : List.of();
        this.menuRegistry = menuRegistry;
        log.debug("SearchService initialisiert mit {} Provider(n)", this.providers.size());
    }

    /**
     * Runs the global search: queries all visible providers and groups the results
     * by module title. The groups appear in the order of their first hit.
     *
     * @param query the search term (raw; is trimmed and capped)
     * @return grouped hit list (never {@code null}, possibly empty)
     */
    public List<SearchResultGroup> search(String query) {
        String q = normalize(query);
        if (q.length() < MIN_QUERY_LENGTH) {
            return List.of();
        }

        Set<String> visibleTitles = safeVisibleMenuTitles();

        // order of the groups = order of the first hit → LinkedHashMap
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
            // Sort by score in descending order within the group and cap.
            hits.sort(Comparator.comparingInt(SearchHit::getScore).reversed());
            List<SearchHit> capped = hits.size() > MAX_HITS_PER_MODULE
                    ? new ArrayList<>(hits.subList(0, MAX_HITS_PER_MODULE))
                    : hits;
            result.add(new SearchResultGroup(e.getKey(), capped));
        }
        return result;
    }

    /**
     * Queries a single provider and catches every error (empty list on problems).
     */
    private List<SearchHit> queryProvider(SearchProvider provider, String query) {
        try {
            List<SearchHit> hits = provider.search(query, MAX_HITS_PER_MODULE);
            if (hits == null) {
                return List.of();
            }
            // Defensive: sort out null hits and hits without a link/title.
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
     * Whether a provider may be queried: cross-cutting root providers always, menu-coupled
     * domain module providers only when the module menu is visible.
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
     * A module counts as visible when its title occurs among the visible menu titles either as a
     * menu title or as the last segment of a full title ({@code "Parent | Titel"}). If the
     * menu registry is empty/unavailable, no filtering takes place (fail-open on visibility; the
     * actual tenant protection is done by each provider itself).
     */
    private boolean isModuleVisible(String moduleTitle, Set<String> visibleTitles) {
        if (visibleTitles.isEmpty()) {
            return true;
        }
        if (visibleTitles.contains(moduleTitle)) {
            return true;
        }
        // Full title "Parent | Titel": match on the last segment.
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
     * A hit list grouped by module.
     *
     * @param module the module (group title)
     * @param hits   the hits of this module (already sorted and capped)
     */
    public record SearchResultGroup(String module, List<SearchHit> hits) {
    }
}

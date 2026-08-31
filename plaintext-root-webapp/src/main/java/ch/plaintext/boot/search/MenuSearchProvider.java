/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import ch.plaintext.MenuRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Root's own {@link SearchProvider}: makes every <b>visible</b> menu page findable by its title
 * ("jump to page X"). Pulls the targets straight from {@link MenuRegistry#getAllMenuItems()} and
 * uses their {@code link} as a deep link - exactly the pattern the concept prescribes.
 * <p>
 * Cross-cutting (not bound to a single module menu), hence {@link #isMenuScoped()}
 * {@code = false}. Visibility is enforced here by the provider itself: only menus are
 * taken into account for which {@link MenuRegistry.MenuItem#isOn()} is true (roles + tenant
 * visibility).
 *
 * @author plaintext.ch
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuSearchProvider implements SearchProvider {

    private final MenuRegistry menuRegistry;

    @Override
    public String providerId() {
        return "menu";
    }

    @Override
    public String moduleTitle() {
        return "Navigation";
    }

    @Override
    public boolean isMenuScoped() {
        // Cross-cutting: bound to no single menu, filters internally via isOn().
        return false;
    }

    @Override
    public List<SearchHit> search(String query, int limit) {
        String needle = query.toLowerCase();

        List<MenuRegistry.MenuItem> items;
        try {
            items = menuRegistry.getAllMenuItems();
        } catch (Exception ex) {
            log.debug("Menü-Items nicht verfügbar: {}", ex.getMessage());
            return List.of();
        }
        if (items == null) {
            return List.of();
        }

        List<SearchHit> hits = new ArrayList<>();
        for (MenuRegistry.MenuItem item : items) {
            SearchHit hit = toHit(item, needle);
            if (hit != null) {
                hits.add(hit);
                if (hits.size() >= limit * 3L) {
                    // coarse cap before sorting/capping in the SearchService
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * Builds a hit from a visible, matching menu item, otherwise {@code null}
     * (incomplete, invisible or no query match).
     */
    private SearchHit toHit(MenuRegistry.MenuItem item, String needle) {
        if (item == null || item.getTitle() == null || item.getLink() == null || item.getLink().isBlank()) {
            return null;
        }
        // Enforce visibility ourselves: only menus that the user/tenant is allowed to see.
        if (!isOnSafe(item)) {
            return null;
        }
        int score = matchScore(item.getTitle(), item.getParent(), needle);
        if (score <= 0) {
            return null;
        }
        return new SearchHitDTO(
                item.getTitle(),
                (item.getParent() != null && !item.getParent().isBlank()) ? item.getParent() : null,
                item.getLink(),
                (item.getIcon() != null && !item.getIcon().isBlank()) ? item.getIcon() : "pi pi-compass",
                score);
    }

    /**
     * Score against the full visible menu path ({@code parent + " " + title}).
     * <p>
     * <b>Single token:</b> weighted as before — exact title &gt; title prefix &gt; contained title
     * &gt; hit in the parent.
     * <p>
     * <b>Several tokens</b> (separated by spaces, "parts"): EVERY token has to occur as a substring
     * somewhere in the path — this way {@code "roo sett"}, for example, finds the entry "Root | Settings"
     * ({@code roo}→Root, {@code sett}→Settings). If a token is missing, there is no hit.
     *
     * @param needle query, already lower-cased
     * @return score &gt; 0 on a hit, otherwise 0
     */
    private int matchScore(String title, String parent, String needle) {
        String t = title.toLowerCase();
        String path = (parent != null && !parent.isBlank()) ? parent.toLowerCase() + " " + t : t;
        String q = needle.trim();
        if (q.isEmpty()) {
            return 0;
        }
        String[] tokens = q.split("\\s+");

        if (tokens.length == 1) {
            String n = tokens[0];
            if (t.equals(n)) {
                return 100;
            }
            if (t.startsWith(n)) {
                return 80;
            }
            if (t.contains(n)) {
                return 60;
            }
            if (parent != null && parent.toLowerCase().contains(n)) {
                return 30;
            }
            return 0;
        }

        // Multiple tokens: ALL parts have to occur in the path (parent + title).
        boolean titleHit = false;
        for (String tok : tokens) {
            if (!path.contains(tok)) {
                return 0;
            }
            if (t.contains(tok)) {
                titleHit = true;
            }
        }
        // Slightly higher when at least one part sits in the title itself (not only in the parent).
        return 50 + (titleHit ? 10 : 0);
    }

    private boolean isOnSafe(MenuRegistry.MenuItem item) {
        try {
            return item.isOn();
        } catch (Exception _) {
            // When in doubt do not display (fail-closed) - visibility is meant to be strict.
            return false;
        }
    }
}

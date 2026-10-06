/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import ch.plaintext.MenuRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Root's own {@link SearchProvider}: makes every <b>visible</b> menu page findable by its title
 * ("jump to page X"), tolerant of typos since card 1348 ({@link FuzzyText}). Pulls the targets straight from {@link MenuRegistry#getAllMenuItems()} and
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
        String needle = FuzzyText.normalize(query);
        if (needle.isEmpty()) {
            return List.of();
        }

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
            }
        }
        // Best first, then a coarse cap; the SearchService caps per group once more. Sorting
        // before the cap matters since card 1348: with fuzzy hits in the list, the first items
        // in menu order are no longer necessarily the best ones.
        hits.sort(Comparator.comparingInt(SearchHit::getScore).reversed());
        long cap = limit * 3L;
        return hits.size() > cap ? new ArrayList<>(hits.subList(0, (int) cap)) : hits;
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
        // Checked BEFORE any matching, so a fuzzy hit can never surface a hidden page either.
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
     * Score against the full visible menu path ({@code parent + " " + title}), both sides
     * {@link FuzzyText#normalize(String) normalised} (case, umlauts, punctuation).
     * <p>
     * <b>Literal tiers (unchanged):</b> single token — exact title (100) &gt; title prefix (80)
     * &gt; contained in the title (60) &gt; contained in the parent (30). Several tokens — EVERY
     * token has to occur somewhere in the path (50, 60 when one sits in the title); this way
     * {@code "roo sett"} finds "Root | Settings".
     * <p>
     * <b>Fuzzy tier (card 1348), only when no literal tier matched:</b> every token has to match
     * the title or the parent approximately ({@link FuzzyText#fuzzyErrors(String, String)}, spaces
     * removed, the allowed number of errors grows with the token length). A title hit scores
     * {@code 45 - 10 * errors}, a pure parent hit {@code 15 - 5 * errors}, at least 1 — always below
     * the literal tiers of the same kind. "wandereise" thus finds "Wanderreisen".
     *
     * @param needle query, already normalised and not empty
     * @return score &gt; 0 on a hit, otherwise 0
     */
    private int matchScore(String title, String parent, String needle) {
        String t = FuzzyText.normalize(title);
        String p = FuzzyText.normalize(parent);
        String[] tokens = needle.split(" ");
        int literal = literalScore(t, p, tokens);
        return literal > 0 ? literal : fuzzyScore(t, p, tokens);
    }

    private static int literalScore(String t, String p, String[] tokens) {
        return tokens.length == 1 ? einWortScore(t, p, tokens[0]) : mehrWortScore(t, p, tokens);
    }

    /** One search word (card 1416, Sonar java:S3776: split out of literalScore). */
    private static int einWortScore(String t, String p, String n) {
        if (t.equals(n)) {
            return 100;
        }
        if (t.startsWith(n)) {
            return 80;
        }
        if (t.contains(n)) {
            return 60;
        }
        return p.contains(n) ? 30 : 0;
    }

    private static int mehrWortScore(String t, String p, String[] tokens) {
        // Multiple tokens: ALL parts have to occur in the path (parent + title).
        String path = p.isEmpty() ? t : p + " " + t;
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

    private static int fuzzyScore(String t, String p, String[] tokens) {
        String tc = FuzzyText.compact(t);
        String pc = FuzzyText.compact(p);
        int errors = 0;
        boolean titleHit = false;
        for (String tok : tokens) {
            int inTitle = FuzzyText.fuzzyErrors(tok, tc);
            int inParent = FuzzyText.fuzzyErrors(tok, pc);
            if (inTitle < 0 && inParent < 0) {
                return 0;
            }
            if (inTitle >= 0 && (inParent < 0 || inTitle <= inParent)) {
                titleHit = true;
                errors += inTitle;
            } else {
                errors += inParent;
            }
        }
        return titleHit ? Math.max(1, 45 - 10 * errors) : Math.max(1, 15 - 5 * errors);
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

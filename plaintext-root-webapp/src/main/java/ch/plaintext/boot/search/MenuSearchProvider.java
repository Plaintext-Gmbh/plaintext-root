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
 * Root-eigener {@link SearchProvider}: macht jede <b>sichtbare</b> Menü-Seite per Titel auffindbar
 * („springe zu Seite X"). Zieht die Ziele direkt aus {@link MenuRegistry#getAllMenuItems()} und
 * verwendet deren {@code link} als Deep-Link – exakt das Muster, das das Konzept vorgibt.
 * <p>
 * Quer schneidend (nicht an ein einzelnes Modul-Menü gebunden), daher {@link #isMenuScoped()}
 * {@code = false}. Die Sichtbarkeit wird hier selbst erzwungen: es werden ausschliesslich Menüs
 * berücksichtigt, für die {@link MenuRegistry.MenuItem#isOn()} true ist (Rollen + Mandanten-
 * Sichtbarkeit).
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
        // Quer schneidend: an kein einzelnes Menü gebunden, filtert intern über isOn().
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
                    // grober Deckel vor dem Sortieren/Cappen im SearchService
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * Baut aus einem sichtbaren, passenden Menü-Item einen Treffer, sonst {@code null}
     * (unvollständig, unsichtbar oder kein Query-Match).
     */
    private SearchHit toHit(MenuRegistry.MenuItem item, String needle) {
        if (item == null || item.getTitle() == null || item.getLink() == null || item.getLink().isBlank()) {
            return null;
        }
        // Sichtbarkeit selbst erzwingen: nur Menüs, die der Benutzer/Mandant sehen darf.
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
     * Score gegen den vollen sichtbaren Menü-Pfad ({@code parent + " " + title}).
     * <p>
     * <b>Einzel-Token:</b> gewichtet wie bisher — exakter Titel &gt; Titel-Präfix &gt; enthaltener Titel
     * &gt; Treffer im Parent.
     * <p>
     * <b>Mehrere Tokens</b> (durch Leerzeichen getrennt, „Teile"): JEDES Token muss als Teilstring
     * irgendwo im Pfad vorkommen — so findet z. B. {@code "roo sett"} den Eintrag „Root | Settings"
     * ({@code roo}→Root, {@code sett}→Settings). Fehlt ein Token, kein Treffer.
     *
     * @param needle bereits klein­geschriebene Query
     * @return Score &gt; 0 bei Treffer, sonst 0
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

        // Mehr-Token: ALLE Teile müssen im Pfad (Parent + Titel) vorkommen.
        boolean titleHit = false;
        for (String tok : tokens) {
            if (!path.contains(tok)) {
                return 0;
            }
            if (t.contains(tok)) {
                titleHit = true;
            }
        }
        // Etwas höher, wenn mindestens ein Teil im Titel selbst sitzt (nicht nur im Parent).
        return 50 + (titleHit ? 10 : 0);
    }

    private boolean isOnSafe(MenuRegistry.MenuItem item) {
        try {
            return item.isOn();
        } catch (Exception _) {
            // Im Zweifel nicht anzeigen (fail-closed) – Sichtbarkeit soll strikt sein.
            return false;
        }
    }
}

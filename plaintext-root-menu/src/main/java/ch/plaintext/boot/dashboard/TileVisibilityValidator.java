/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.MenuRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates at startup that the tenant-specific visibility of every dashboard tile is really
 * coupled to a registered menu title (see the security finding on commit {@code d041891}).
 * <p>
 * Background: tile visibility hinges on an exact string comparison of
 * {@link TileItemImpl#getVisibilityTitle()} against the menu titles hidden for the tenant.
 * With the blacklist default, everything that does <em>not</em> match exactly stays visible. On a
 * title mismatch (typo, missing {@code menuTitle}, hierarchical title) a tile therefore remains
 * visible even though the corresponding menu entry is hidden for the tenant (fail-open). This
 * validator makes such a misconfiguration <strong>loud</strong> at startup (WARN logging) instead
 * of letting it pass silently – which effectively makes {@code menuTitle} mandatory.
 *
 * @author plaintext.ch
 */
@Slf4j
public class TileVisibilityValidator implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;

    public TileVisibilityValidator(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<TileVisibilityIssue> issues = validate();
        for (TileVisibilityIssue issue : issues) {
            log.warn("Dashboard-Kachel '{}' (Sichtbarkeits-Titel '{}'): {} – "
                    + "die mandatsspezifische Sichtbarkeit greift evtl. nicht (fail-open). "
                    + "Bitte 'menuTitle' der @DashboardTile auf einen registrierten Menü-Titel setzen.",
                issue.tileId(), issue.visibilityTitle(), issue.reason().getMessage());
        }
        if (issues.isEmpty()) {
            log.info("Dashboard-Kachel-Validierung OK: alle Kacheln sind an einen registrierten "
                + "Menü-Titel gekoppelt");
        } else {
            log.warn("Dashboard-Kachel-Validierung: {} Kachel(n) mit fragwürdiger Menü-Kopplung "
                + "(siehe Warnungen oben)", issues.size());
        }
    }

    /**
     * Checks all registered tiles against the registered menu titles.
     *
     * @return list of the issues found (empty if everything is coupled correctly)
     */
    List<TileVisibilityIssue> validate() {
        Collection<TileItemImpl> tiles = applicationContext.getBeansOfType(TileItemImpl.class).values();
        if (tiles.isEmpty()) {
            return List.of();
        }

        Set<String> menuTitles = loadMenuTitles();

        List<TileVisibilityIssue> issues = new ArrayList<>();
        for (TileItemImpl tile : tiles) {
            String menuTitle = tile.getMenuTitle();
            String visibilityTitle = tile.getVisibilityTitle();

            if (menuTitle == null || menuTitle.trim().isEmpty()) {
                // menuTitle is effectively mandatory: without it the coupling falls back to the
                // tile title, which rarely matches a menu title exactly.
                issues.add(new TileVisibilityIssue(tile.getId(), visibilityTitle,
                    IssueReason.MISSING_MENU_TITLE));
            } else if (!menuTitles.isEmpty() && !menuTitles.contains(visibilityTitle)) {
                // Menu titles are registered, but none matches exactly -> configuration error.
                issues.add(new TileVisibilityIssue(tile.getId(), visibilityTitle,
                    IssueReason.NO_MATCHING_MENU));
            }
        }
        return issues;
    }

    private Set<String> loadMenuTitles() {
        try {
            MenuRegistry menuRegistry = applicationContext.getBean(MenuRegistry.class);
            Set<String> titles = new HashSet<>(menuRegistry.getAllMenuTitles());
            if (titles.isEmpty()) {
                log.debug("Keine Menü-Titel registriert – Kachel-Abgleich gegen Menü übersprungen");
            }
            return titles;
        } catch (Exception e) {
            log.debug("Keine MenuRegistry verfügbar – Kachel-Abgleich gegen Menü übersprungen: {}",
                e.getMessage());
            return Set.of();
        }
    }

    /**
     * An issue with a tile-to-menu coupling found during validation.
     *
     * @param tileId          the technical ID of the affected tile
     * @param visibilityTitle the title against which visibility would be checked
     * @param reason          the kind of issue
     */
    record TileVisibilityIssue(String tileId, String visibilityTitle, IssueReason reason) {
    }

    /**
     * Kind of coupling issue.
     */
    enum IssueReason {
        /** No {@code menuTitle} is set (effectively mandatory). */
        MISSING_MENU_TITLE("kein menuTitle gesetzt"),
        /** The {@code menuTitle} does not match any registered menu title. */
        NO_MATCHING_MENU("menuTitle passt zu keinem registrierten Menü-Titel");

        private final String message;

        IssueReason(String message) {
            this.message = message;
        }

        String getMessage() {
            return message;
        }
    }
}

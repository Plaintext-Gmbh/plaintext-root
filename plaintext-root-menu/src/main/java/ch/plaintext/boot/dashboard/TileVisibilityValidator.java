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
 * Validiert beim Start, dass die mandatsspezifische Sichtbarkeit jeder Dashboard-Kachel
 * tatsächlich an einen registrierten Menü-Titel gekoppelt ist (siehe Sicherheitsbefund zu
 * Commit {@code d041891}).
 * <p>
 * Hintergrund: Die Kachel-Sichtbarkeit hängt an einem exakten String-Abgleich von
 * {@link TileItemImpl#getVisibilityTitle()} gegen die im Mandanten ausgeblendeten Menü-Titel.
 * Im Blacklist-Standard ist alles sichtbar, was <em>nicht</em> exakt matcht. Bei einem
 * Titel-Mismatch (Tippfehler, fehlendes {@code menuTitle}, hierarchischer Titel) bleibt eine
 * Kachel also sichtbar, obwohl der zugehörige Menüeintrag für den Mandanten ausgeblendet ist
 * (fail-open). Dieser Validator macht eine solche Fehlkonfiguration beim Start <strong>laut</strong>
 * (WARN-Logging) statt sie still durchzulassen – {@code menuTitle} wird damit faktisch
 * verpflichtend.
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
     * Prüft alle registrierten Kacheln gegen die registrierten Menü-Titel.
     *
     * @return Liste der gefundenen Probleme (leer, wenn alles korrekt gekoppelt ist)
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
                // menuTitle ist faktisch verpflichtend: ohne ihn fällt die Kopplung auf den
                // Kachel-Titel zurück, der selten exakt einem Menü-Titel entspricht.
                issues.add(new TileVisibilityIssue(tile.getId(), visibilityTitle,
                    IssueReason.MISSING_MENU_TITLE));
            } else if (!menuTitles.isEmpty() && !menuTitles.contains(visibilityTitle)) {
                // Es sind Menü-Titel registriert, aber keiner matcht exakt -> Konfig-Fehler.
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
     * Ein bei der Validierung gefundenes Problem einer Kachel-Menü-Kopplung.
     *
     * @param tileId          die technische ID der betroffenen Kachel
     * @param visibilityTitle der Titel, gegen den die Sichtbarkeit geprüft würde
     * @param reason          die Art des Problems
     */
    record TileVisibilityIssue(String tileId, String visibilityTitle, IssueReason reason) {
    }

    /**
     * Art eines Kopplungs-Problems.
     */
    enum IssueReason {
        /** Es ist kein {@code menuTitle} gesetzt (faktisch verpflichtend). */
        MISSING_MENU_TITLE("kein menuTitle gesetzt"),
        /** Der {@code menuTitle} entspricht keinem registrierten Menü-Titel. */
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

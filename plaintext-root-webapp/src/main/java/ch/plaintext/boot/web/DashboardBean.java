/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.DashboardTileData;
import ch.plaintext.boot.dashboard.DashboardTileModelBuilder;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Backing bean of the start page (dashboard). Builds the visible module tiles on every
 * page call - analogous to the {@link MenuBean}, which rebuilds the menu model per view.
 *
 * @author plaintext.ch
 *
 * <h2>Why field injection stays here (java:S6813, card 1273)</h2>
 *
 * <p>This bean is <b>view-scoped and serializable</b>, and with server-side state saving the JSF
 * view state is held in the session and written out with it. On a deserialization <b>no
 * constructor runs</b>: a service field set only through the
 * constructor stays {@code null} forever, and being {@code final} nothing can set it afterwards,
 * not even the context. A {@code NotSerializableException} would thereby turn into a permanent
 * {@code NullPointerException} (cards 915/1246). Field injection ({@code @Autowired}, not
 * {@code final}) lets the context refill the field after a deserialization — that is the house
 * rule, and {@code PlaintextSessionBeanSerialisierbarTest} enforces it as a build-breaking
 * guard.</p>
 *
 * <p>{@code java:S6813} demands the exact opposite at these fields, so both cannot hold at once.
 * The guard wins: it protects against a defect that is silent and permanent, the rule protects a
 * style. The suppression sits on the class because every injected service of such a bean falls
 * under the house rule — card 1273 carries the measurement and the decision.</p>
 */
@Component("dashboardBean")
@Scope("view")
@Slf4j
@SuppressWarnings("java:S6813") // begruendet im Klassenkommentar oben (Karte 1273) — nicht umbauen
public class DashboardBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    private DashboardTileModelBuilder dashboardTileModelBuilder;

    @Getter
    private List<DashboardTileData> tiles = new ArrayList<>();

    @PostConstruct
    public void init() {
        try {
            tiles = dashboardTileModelBuilder.buildTiles();
            log.debug("Dashboard initialisiert mit {} Kacheln", tiles.size());
        } catch (Exception e) {
            // Defensive: a faulty tile must not shoot down the start page with a render 500
            log.error("Fehler beim Aufbau des Dashboards: {}", e.getMessage(), e);
            tiles = new ArrayList<>();
        }
    }
}

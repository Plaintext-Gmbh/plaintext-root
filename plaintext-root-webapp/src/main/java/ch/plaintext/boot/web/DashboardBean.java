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
 * Backing-Bean der Startseite (Dashboard). Baut bei jedem Seitenaufruf die sichtbaren
 * Modul-Kacheln auf – analog zum {@link MenuBean}, der das Menü-Modell pro View neu aufbaut.
 *
 * @author plaintext.ch
 */
@Component("dashboardBean")
@Scope("view")
@Slf4j
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
            // Defensive: Eine fehlerhafte Kachel darf die Startseite nicht mit Render-500 abschiessen
            log.error("Fehler beim Aufbau des Dashboards: {}", e.getMessage(), e);
            tiles = new ArrayList<>();
        }
    }
}

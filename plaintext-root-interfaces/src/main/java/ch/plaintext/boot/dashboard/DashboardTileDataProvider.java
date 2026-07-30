/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.DashboardTileData;

/**
 * Bean-Interface, über das ein Modul einer Dashboard-Kachel dynamische Inhalte liefert
 * (Status-Text, Status-Farbe, Info, Aktionen, Dropdown).
 * <p>
 * Eine Implementierung wird als Spring-Bean registriert. Beim Aufbau des Dashboards werden alle
 * Provider gesammelt und – anhand von {@link #tileId()} – der jeweils passenden Kachel zugeordnet.
 * Existiert kein Provider für eine Kachel, werden nur die statischen Metadaten der
 * {@link DashboardTile}-Annotation angezeigt.
 *
 * @author plaintext.ch
 */
public interface DashboardTileDataProvider {

    /**
     * Die ID der Kachel ({@link DashboardTile#id()}), die dieser Provider anreichert.
     *
     * @return die Kachel-ID
     */
    String tileId();

    /**
     * Reichert die übergebene Kachel mit dynamischen Inhalten an. Wird pro Seitenaufruf im
     * Sicherheits-/Mandantenkontext des aktuellen Benutzers aufgerufen.
     *
     * @param tile die anzureichernde Kachel (Metadaten sind bereits gesetzt)
     */
    void enrich(DashboardTileData tile);
}

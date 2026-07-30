/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.util.List;

/**
 * Zugriff auf alle registrierten Dashboard-Kacheln – analog zu {@link MenuRegistry}.
 * <p>
 * Liefert die {@code @DashboardTile}-annotierten Klassen als {@link TileItem}, die für Konfigurations-
 * oder Admin-Oberflächen sowie zum Aufbau der Startseite verwendet werden können.
 *
 * @author plaintext.ch
 */
public interface TileRegistry {

    /**
     * Liefert die Titel aller registrierten Kacheln.
     *
     * @return Liste aller Kachel-Titel
     */
    List<String> getAllTileTitles();

    /**
     * Liefert alle registrierten Kacheln mit ihren Metadaten.
     *
     * @return Liste aller Kacheln
     */
    List<TileItem> getAllTileItems();

    /**
     * Eine registrierte Dashboard-Kachel mit ihren Metadaten.
     */
    interface TileItem {

        /** @return die technische ID der Kachel. */
        String getId();

        /** @return den Titel der Kachel. */
        String getTitle();

        /** @return die Icon-Klasse oder leeren String. */
        String getIcon();

        /** @return die Bild-URL oder leeren String. */
        String getImage();

        /** @return den Haupt-Link oder leeren String. */
        String getLink();

        /** @return die Sortierreihenfolge (kleinere Werte zuerst). */
        int getOrder();

        /** @return die erlaubten Rollen oder leere Liste, wenn für alle sichtbar. */
        List<String> getRoles();

        /**
         * @return den Menü-Titel, gegen den die mandatsspezifische Sichtbarkeit geprüft wird.
         */
        String getMenuTitle();

        /**
         * Prüft, ob die Kachel für den aktuellen Benutzer sichtbar ist (Rollen- und
         * mandatsspezifische Sichtbarkeit kombiniert).
         *
         * @return true, wenn sichtbar
         */
        boolean isOn();
    }
}

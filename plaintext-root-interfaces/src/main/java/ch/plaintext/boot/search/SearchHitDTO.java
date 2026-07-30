/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Einfache, wiederverwendbare {@link SearchProvider.SearchHit}-Implementierung, damit ein Provider
 * keine eigene innere Klasse braucht. Ein Modul baut seine Treffer typischerweise so:
 * <pre>{@code
 * new SearchHitDTO(k.getTitel(), k.getDatum().toString(),
 *                  "korrespondenz.html?id=" + k.getId(), "pi pi-envelope", score)
 * }</pre>
 *
 * @author plaintext.ch
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchHitDTO implements SearchProvider.SearchHit, Serializable {

    private static final long serialVersionUID = 1L;

    /** Haupttext des Treffers. */
    private String title;

    /** Kontext-Zeile (Datum/Mandant/Kurzbeschreibung), darf {@code null} sein. */
    private String subtitle;

    /** Deep-Link auf die Modul-Zielseite (relativ zum Context-Path), wie ein {@code MenuAnnotation.link}. */
    private String link;

    /** PrimeFaces-Icon-Klasse, darf {@code null} sein. */
    private String icon;

    /** Ranking innerhalb der Modul-Gruppe (höher = weiter oben). */
    private int score;
}

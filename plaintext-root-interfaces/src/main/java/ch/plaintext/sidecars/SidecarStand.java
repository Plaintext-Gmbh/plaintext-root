/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars;

import java.time.Instant;
import java.util.List;

/**
 * Letzter bekannter Stand eines Sidecars (Karte 1400).
 *
 * @param name         Name
 * @param titel        Titel aus der Beschreibung oder {@code null}
 * @param version      Version des Dienstes oder {@code null}
 * @param erreichbar   ob die letzte Abfrage eine gültige Beschreibung geliefert hat
 * @param status       {@code ok}, {@code eingeschraenkt}, {@code fehler} oder {@code unbekannt}
 * @param auth         {@code NICHT_NOETIG}, {@code KEIN_TOKEN}, {@code GUELTIG}, {@code UNGUELTIG}, {@code UNBEKANNT}
 * @param faehigkeiten Ids der Fähigkeiten
 * @param abgefragt    Zeitpunkt der letzten Abfrage oder {@code null}
 */
public record SidecarStand(String name, String titel, String version, boolean erreichbar, String status,
                           String auth, List<String> faehigkeiten, Instant abgefragt) {
}

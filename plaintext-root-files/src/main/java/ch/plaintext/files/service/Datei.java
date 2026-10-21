/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files.service;

import java.time.Instant;

/**
 * Eine Datei der persönlichen Ablage (Karte 1559, aus dem Inventar).
 *
 * @param name      Dateiname ohne Pfad
 * @param groesse   Bytes
 * @param geaendert letzte Änderung (= Hochladen)
 * @param ablauf    ab dann ist die Datei weg
 */
public record Datei(String name, long groesse, Instant geaendert, Instant ablauf) {
}

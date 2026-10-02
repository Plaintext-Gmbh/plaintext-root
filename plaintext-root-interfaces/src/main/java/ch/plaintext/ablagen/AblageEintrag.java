/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.ablagen;

import java.time.Instant;

/**
 * Ein Eintrag in einer {@link DateiAblage} (Karte 1406).
 *
 * @param pfad      relativ zur Ablage
 * @param ordner    {@code true} bei einem Ordner
 * @param groesse   Bytes, -1 wenn unbekannt
 * @param geaendert letzte Änderung oder {@code null}
 */
public record AblageEintrag(String pfad, boolean ordner, long groesse, Instant geaendert) {
}

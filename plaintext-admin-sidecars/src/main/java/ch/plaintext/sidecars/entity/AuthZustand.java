/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.entity;

/** Ob der Zugriff auf einen Sidecar gesichert ist (Karte 1400). */
public enum AuthZustand {
    /** der Sidecar verlangt keinen Token ({@code auth.art = keine}) */
    NICHT_NOETIG,
    /** der Sidecar verlangt einen, es ist keiner hinterlegt */
    KEIN_TOKEN,
    /** {@code /.well-known/plaintext-sidecar/auth} hat den hinterlegten Token mit 200 bestätigt */
    GUELTIG,
    /** {@code /auth} hat mit 401 geantwortet */
    UNGUELTIG,
    /** nicht geprüft (nicht erreichbar oder unerwartete Antwort) */
    UNBEKANNT
}

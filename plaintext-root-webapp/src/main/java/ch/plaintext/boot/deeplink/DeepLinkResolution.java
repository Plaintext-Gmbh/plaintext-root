/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

/**
 * Ergebnis der Deep-Link-Aufloesung (Karte 345).
 *
 * @param ergebnis  Ausgang der Pruefung
 * @param zielPfad  bei {@link Ergebnis#OK} der Pfad relativ zum Context-Path
 *                  (z.B. {@code /auszahlungen.html?id=42}), sonst {@code null}
 */
public record DeepLinkResolution(DeepLinkResolution.Ergebnis ergebnis, String zielPfad) {

    /**
     * Bewusst nur ein einziger „nein"-Grund nach aussen (die Fehlerseite unterscheidet nicht):
     * wer einen fremden Datensatz raet, soll nicht am Unterschied zwischen „gibt es nicht" und
     * „darfst du nicht" ablesen koennen, ob die Id existiert. Die Unterscheidung dient dem Log
     * und den Tests.
     */
    public enum Ergebnis {
        /** Alles geprueft, Mandat gewechselt, Weiterleitung erlaubt. */
        OK,
        /** Parameter fehlen oder verletzen das Zeichenmuster. */
        UNGUELTIGE_PARAMETER,
        /** Kein Modul hat sich fuer diesen {@code type} registriert — fail-closed. */
        UNBEKANNTER_TYP,
        /** Der Benutzer hat auf das Ziel-Mandat keinen Zugriff. Kein Wechsel, auch nicht kurz. */
        MANDAT_VERWEIGERT,
        /** Der Benutzer darf diesen Datensatz nicht sehen (oder es gibt ihn nicht). */
        DATENSATZ_VERWEIGERT
    }

    public boolean erlaubt() {
        return ergebnis == Ergebnis.OK;
    }

    public static DeepLinkResolution ok(String zielPfad) {
        return new DeepLinkResolution(Ergebnis.OK, zielPfad);
    }

    public static DeepLinkResolution abgelehnt(Ergebnis grund) {
        return new DeepLinkResolution(grund, null);
    }
}

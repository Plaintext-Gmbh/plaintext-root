/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

/**
 * Result of the deep-link resolution (card 345).
 *
 * @param ergebnis  outcome of the check
 * @param zielPfad  on {@link Ergebnis#OK} the path relative to the context path
 *                  (e.g. {@code /auszahlungen.html?id=42}), otherwise {@code null}
 */
public record DeepLinkResolution(DeepLinkResolution.Ergebnis ergebnis, String zielPfad) {

    /**
     * Deliberately only a single "no" reason towards the outside (the error page does not
     * differentiate): whoever guesses a foreign record must not be able to tell from the difference
     * between "does not exist" and "you are not allowed" whether the id exists. The distinction
     * serves the log and the tests.
     */
    public enum Ergebnis {
        /** Everything checked, tenant switched, redirect permitted. */
        OK,
        /** Parameters are missing or violate the character pattern. */
        UNGUELTIGE_PARAMETER,
        /** No module has registered for this {@code type} — fail-closed. */
        UNBEKANNTER_TYP,
        /** The user has no access to the target tenant. No switch, not even briefly. */
        MANDAT_VERWEIGERT,
        /** The user must not see this record (or it does not exist). */
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

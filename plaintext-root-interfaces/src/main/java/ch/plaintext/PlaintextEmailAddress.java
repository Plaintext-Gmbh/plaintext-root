/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Entscheidet an EINER Stelle, ob eine Zeichenkette als Mailadresse zustellbar ist.
 *
 * <p><b>Warum es das braucht (Karte 596):</b> In dieser Anwendung ist der Benutzername zugleich
 * die Mailadresse — die Selbstregistrierung setzt ihn so
 * ({@code RegistrationService: user.setUsername(token.getEmail())}), der Passwort-Reset verschickt
 * an ihn, und die Benutzerverwaltung erzwingt beim Anlegen die Mailform. <b>Erzwungen wird das aber
 * erst seit dieser Prüfung und nur über die Oberfläche</b>: Im Altbestand stehen Namen wie
 * {@code plafferma}, und maschinelle Schreiber hinterlassen {@code anonymousUser}. Wer den
 * Benutzernamen ungeprüft als Empfänger verwendet, erzeugt genau dort einen stillen Fehlschlag.
 *
 * @author worker01
 * @since 07.08.2026
 */
public final class PlaintextEmailAddress {

    /** Gleicher Ausdruck wie in der Benutzerverwaltung (MyUserBackingBean), damit beide dasselbe akzeptieren. */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    private PlaintextEmailAddress() {
        // Utility-Klasse
    }

    /**
     * Ob der Wert als Mailadresse zustellbar ist.
     *
     * @param wert zu prüfender Wert, darf {@code null} sein
     * @return true, wenn der Wert die Form einer Mailadresse hat
     */
    public static boolean isDeliverable(String wert) {
        return wert != null && EMAIL_PATTERN.matcher(wert.trim()).matches();
    }

    /**
     * Liefert den Wert als zustellbare Adresse — oder nichts.
     *
     * <p>Der {@link Optional}-Rückgabewert ist Absicht: Er zwingt den Aufrufer, den Fall
     * „nicht zustellbar" zu behandeln, statt eine unbrauchbare Adresse weiterzureichen.
     *
     * @param wert zu prüfender Wert, darf {@code null} sein
     * @return die getrimmte Adresse, oder {@link Optional#empty()} wenn nicht zustellbar
     */
    public static Optional<String> asDeliverable(String wert) {
        return isDeliverable(wert) ? Optional.of(wert.trim()) : Optional.empty();
    }
}

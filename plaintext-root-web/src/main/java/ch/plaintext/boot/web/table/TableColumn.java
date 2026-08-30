/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.table;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

/**
 * Eine ein- und ausblendbare Spalte einer Tabelle mit Anzeige-Einstellungen.
 *
 * <p>Der {@code key} ist der Vertrag zwischen drei Stellen, die sich sonst nicht kennen: der Seite
 * (welche Spalte gerendert wird), dem gespeicherten Benutzerstand (welche Breite und Sichtbarkeit
 * dazugehoert) und dem Resize-Ereignis von PrimeFaces. Letzteres liefert <b>keine</b>
 * Spaltenkennung mit, sondern nur den Kopftext — deshalb traegt jede Spalte ihr {@code label}
 * mit, ueber das {@link TableSettings#keyFromHeader(String)} zurueckrechnet.</p>
 *
 * <p>Daraus folgt eine Regel, die man beim Lesen nicht sieht: <b>zwei Spalten derselben Tabelle
 * duerfen nicht denselben Kopftext haben.</b> Sonst landet eine gezogene Breite bei der falschen
 * Spalte. Ein Kopftext, der doppelt vorkommt, ist ohnehin fuer den Benutzer mehrdeutig.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Data
@AllArgsConstructor
public class TableColumn implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Stabiler Schluessel, z.B. {@code "owner"}. Aendert sich nie — er steht in gespeicherten Staenden. */
    private String key;

    /** Kopftext der Spalte, wie er in der Tabelle steht, z.B. {@code "Owner"}. */
    private String label;

    /** Vorgabebreite in Pixeln; {@code 0} heisst "keine feste Breite" (Auto-Layout). */
    private int defaultWidth;

    /** Startet die Spalte sichtbar, solange der Benutzer nichts verstellt hat? */
    private boolean defaultVisible;

    public TableColumn(String key, String label, int defaultWidth) {
        this(key, label, defaultWidth, true);
    }
}

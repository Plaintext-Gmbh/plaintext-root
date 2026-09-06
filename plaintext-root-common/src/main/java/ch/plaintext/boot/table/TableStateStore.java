/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.table;

import java.util.List;

/**
 * Woher der Tabellenstand eines Benutzers kommt und wohin er geht.
 *
 * <p><b>Die mitgelieferte Ablage.</b> Seit Karte 1077 bringt root eine Umsetzung mit:
 * {@link UserPreferenceTableStateStore} legt den Stand je Benutzer <i>und Mandant</i> in
 * {@code UserPreference} ab — als JSON in der Spalte, die es schon gibt. Eine App braucht dafuer
 * keine Zeile Code: die Backing-Bean laesst sich den {@code TableStateStore} injizieren und
 * reicht ihn an {@link TableSettings#init} weiter.</p>
 *
 * <p><b>Warum trotzdem eine Schnittstelle.</b> Die Anzeige-Einstellungen sind ueberall gleich,
 * ihre Ablage nicht ueberall: eine App sichert ihren Stand zusaetzlich nach Confluence, weil
 * ihre Datenbank nach jedem Neustart leer ist. Wer so etwas braucht, setzt diese Schnittstelle
 * selbst um und markiert seine Bean als {@code @Primary}; {@link TableSettings} merkt davon
 * nichts.</p>
 *
 * <p><b>Wer speichert, entscheidet auch ueber den Benutzer.</b> {@link TableSettings} kennt nur
 * den Seitenschluessel. Wem der Stand gehoert, weiss allein die Ablage (Spring Security, ein
 * Funktionsuser, eine Session) — die Umsetzung setzt das im Store davor.</p>
 *
 * <p><b>Aufrufhaeufigkeit.</b> {@code save} laeuft nach jeder Aenderung an der Anzeige, also
 * durchaus mehrmals je Sekunde, wenn jemand Haken setzt. Eine Umsetzung, die dabei synchron ins
 * Netz greift, macht die Spaltenwahl zaeh; die Sicherung nach aussen gehoert hinter eine
 * Verzoegerung.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public interface TableStateStore {

    /**
     * Gespeicherter Stand der Seite fuer den aktuellen Benutzer.
     *
     * @param page Seitenschluessel, z.B. {@code "projects"}
     * @return der Stand; nie {@code null} — ohne gespeicherten Stand ein frischer
     */
    TableState load(String page);

    /**
     * Wie {@link #load(String)}, aber mit den Spaltenschluesseln der Seite.
     *
     * <p>Die Vorgabe reicht an {@link #load(String)} durch. Eine Ablage, die einen <b>Altbestand
     * in anderem Format</b> uebernimmt, braucht die Schluessel: eine gespeicherte Liste
     * <i>sichtbarer</i> Spalten sagt nur mit dem vollstaendigen Spaltensatz, welche Spalten
     * <i>ausgeblendet</i> waren. Genau das tut {@link UserPreferenceTableStateStore} mit
     * {@code UserPreference.tabellenSpalten}.</p>
     *
     * @param page       Seitenschluessel
     * @param columnKeys alle Spaltenschluessel der Seite, in Tabellenreihenfolge
     * @return der Stand; nie {@code null}
     */
    default TableState load(String page, List<String> columnKeys) {
        return load(page);
    }

    /**
     * Legt den Stand der Seite fuer den aktuellen Benutzer ab.
     *
     * @param page  Seitenschluessel, z.B. {@code "projects"}
     * @param state der zu sichernde Stand
     */
    void save(String page, TableState state);
}

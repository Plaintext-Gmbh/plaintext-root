/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.table;

/**
 * Woher der Tabellenstand eines Benutzers kommt und wohin er geht.
 *
 * <p><b>Warum eine Schnittstelle und keine fertige Ablage.</b> Die Anzeige-Einstellungen sind
 * ueberall gleich, ihre Ablage nirgends: die eine App haengt sie an ihre Benutzertabelle, die
 * naechste an eine JSON-Spalte, die dritte sichert sie zusaetzlich nach Confluence, weil ihre
 * Datenbank naechtlich neu aufgebaut wird. Eine mitgelieferte Entitaet muesste eine dieser
 * Entscheidungen fuer alle treffen — und braechte JPA in ein Modul, das sonst keines braucht.</p>
 *
 * <p><b>Wer speichert, entscheidet auch ueber den Benutzer.</b> {@link TableSettings} kennt nur
 * den Seitenschluessel. Wem der Stand gehoert, weiss allein die App (Spring Security, ein
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
     * Legt den Stand der Seite fuer den aktuellen Benutzer ab.
     *
     * @param page  Seitenschluessel, z.B. {@code "projects"}
     * @param state der zu sichernde Stand
     */
    void save(String page, TableState state);
}

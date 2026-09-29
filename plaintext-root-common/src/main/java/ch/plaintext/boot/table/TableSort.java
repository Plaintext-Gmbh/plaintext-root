/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.table;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Eine sortierte Spalte im gespeicherten Stand (Karte 1346).
 *
 * <p>Die Reihenfolge in {@link TableState#getSortBy()} ist die Prioritaet: der erste Eintrag
 * sortiert zuerst, die weiteren nur bei Gleichstand (PrimeFaces {@code sortMode="multiple"},
 * seit PrimeFaces 12 die Vorgabe jeder {@code p:dataTable}).</p>
 *
 * <p><b>Warum zwei Namen fuer eine Spalte.</b> {@link #column} ist der Spaltenschluessel der
 * Seite ({@link TableColumn#getKey()}); an ihm haengt die Frage "ist die Spalte sichtbar?".
 * {@link #field} ist, was PrimeFaces zum Wiederfinden der Spalte braucht: das Feld aus
 * {@code sortBy="#{m.displayName}"}, also {@code displayName}. Ohne passendes Feld wirft
 * PrimeFaces beim Aufbau der Tabelle eine {@code FacesException} — deshalb gibt
 * {@link TableSettings#getSortMeta()} nur Eintraege heraus, deren Spalte sichtbar ist.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableSort implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Spaltenschluessel der Seite, z.B. {@code "name"}. */
    private String column;

    /** Sortierfeld fuer PrimeFaces, z.B. {@code "displayName"}. */
    private String field;

    /** Absteigend sortiert? Sonst aufsteigend. */
    private boolean descending;
}

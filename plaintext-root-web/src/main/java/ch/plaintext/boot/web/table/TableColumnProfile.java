/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.table;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ein benanntes Spaltenprofil: Breiten und Sichtbarkeiten in einem.
 *
 * <p>Wer eine Tabelle mal breit mit wenigen und mal schmal mit vielen Spalten braucht, haelt
 * beides unter einem Namen zusammen, statt bei jedem Wechsel zwanzig Haken neu zu setzen. Die
 * Profile haengen im {@link TableState} und werden mit ihm als Ganzes gespeichert.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Data
public class TableColumnProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Spaltenschluessel -&gt; Breite inkl. Einheit, z.B. {@code owner -> 180px}. */
    private Map<String, String> columnWidths = new LinkedHashMap<>();

    /** Spaltenschluessel -&gt; sichtbar. */
    private Map<String, Boolean> columnVisible = new LinkedHashMap<>();

    /** Gesamtbreite der Tabelle in Pixeln, {@code null} = aus den Spalten rechnen. */
    private Integer totalWidth;

    /** Breite, auf die der Knopf im Spaltenkopf eine einzelne Spalte setzt. */
    private Integer targetColumnWidth;
}

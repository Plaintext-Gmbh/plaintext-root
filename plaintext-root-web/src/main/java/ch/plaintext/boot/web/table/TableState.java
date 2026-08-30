/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.table;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Was ein Benutzer sich an einer Tabelle eingerichtet hat — seitenneutral.
 *
 * <p>Reines Datenobjekt: kein Spring, kein JPA, keine Faces-Abhaengigkeit. Genau deshalb kann die
 * App es ablegen, wo sie will — als JSON-Spalte, als Datei, als Confluence-Sicherung. Die
 * Anbindung laeuft ueber {@link TableStateStore}.</p>
 *
 * <p><b>Neue Felder duerfen jederzeit dazukommen.</b> Wer den Stand als JSON ablegt, liest
 * aeltere Staende weiter: unbekannte Felder ignoriert ein Mapper, fehlende bleiben auf ihrem
 * Default. Ein Feld <i>umbenennen</i> ist dagegen ein Bruch — der alte Stand faellt dann still
 * auf die Vorgabe zurueck, und der Benutzer findet seine Spalten neu sortiert vor.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Data
public class TableState implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Sichtbarkeit der Spalten, Schluessel ist {@link TableColumn#getKey()}.
     * Nicht enthaltene Spalten starten mit ihrer Vorgabe.
     */
    private Map<String, Boolean> columnVisible = new LinkedHashMap<>();

    /**
     * Spaltenbreiten, Wert ist die CSS-Breite inkl. Einheit (z.B. {@code "180px"}).
     * Nicht enthaltene Spalten rendern mit ihrer Standardbreite.
     */
    private Map<String, String> columnWidths = new LinkedHashMap<>();

    /**
     * Gesamtbreite der Tabelle in Pixeln, oder {@code null} fuer "aus den Spalten rechnen".
     * Warum es diese Angabe ueberhaupt gibt, steht bei
     * {@link TableSettings#getTabellenBreite()}.
     */
    private Integer totalWidth;

    /** Breite, auf die der Knopf im Spaltenkopf eine einzelne Spalte setzt. */
    private Integer targetColumnWidth;

    /**
     * Steht der Bereich "Anzeige" offen? Das gehoert in den gespeicherten Stand und nicht in die
     * Bean: der Bereich wird bei jeder Spaltenaenderung mitgerendert, und ein Wert, der nur in der
     * Bean auf "zu" startet, klappte ihn dabei jedes Mal zu — mitten im Einrichten.
     */
    private boolean colsExpanded;

    /**
     * Benannte Spaltenprofile. Der Schluessel ist der vom Benutzer vergebene Name; er dient
     * zugleich als Anzeige im Auswahlfeld.
     */
    private Map<String, TableColumnProfile> profiles = new LinkedHashMap<>();

    /** Zuletzt angewendetes Profil — nur fuer die Vorauswahl im Feld. */
    private String activeProfile = "";
}

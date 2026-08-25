/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;
import ch.plaintext.boot.plugins.objstore.SimpleStorable;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

@Slf4j
@Data
public class UserPreference implements SimpleStorable<UserPreference>, Serializable {

    private String menuMode = "layout-sidebar";

    private String darkMode = "light";

    private String componentTheme = "green";

    private String topbarTheme = "light";

    private String menuTheme = "light";

    private String inputStyle = "outlined";

    private boolean lightLogo = false;

    private boolean menuStatic = true;  // Sidebar is expanded/pinned by default

    private String customColor;  // Hex color like "#FF5733" for custom theme, null = use predefined

    private List<NamedColor> customColors = new ArrayList<>();

    private Set<String> hiddenColors = new HashSet<>();

    /**
     * Je Tabelle die Spalten, die dieser Benutzer sehen will (Auftrag Daniel, 25.08.2026:
     * „gerne oben noch eine Auswahl mit Checkbox, dass man sich pro Benutzer die Auswahl der
     * Spalten speichern kann").
     *
     * <p>Schluessel ist eine Tabellen-Kennung ({@code "useradmin"}), Wert die sichtbaren
     * Spaltenschluessel. Bewusst als Karte und nicht als eigenes Feld je Tabelle: die naechste
     * Tabelle, die das braucht, kommt ohne Aenderung an dieser Klasse aus.
     *
     * <p>Ein <b>fehlender</b> Eintrag heisst „noch nie etwas ausgewaehlt" und muss von der
     * jeweiligen Tabelle als ihre Voreinstellung ausgelegt werden — nicht als „alle Spalten aus".
     * Gespeichert wird als JSON ({@code SimpleStorableConverter}), ein neues Feld ist fuer
     * bestehende Datensaetze deshalb unkritisch.
     */
    private Map<String, List<String>> tabellenSpalten = new HashMap<>();

    /**
     * Karte 937: Breite des Wiki-Seitenbaums in Pixeln, {@code 0} = Vorgabe des Layouts.
     *
     * <p><b>Warum hier und nicht in einer eigenen Ablage von app.</b> Das ist Layout-Zustand je
     * Benutzer — genau wie {@link #menuStatic} eine Zeile darueber. Eine zweite Ablage fuer
     * Benutzereinstellungen waere ein konkurrierendes Muster: wer sie spaeter sucht, faende zwei
     * Orte und muesste raten, welcher gilt.
     *
     * <p><b>Warum Pixel und nicht Prozent:</b> Ein Baum braucht eine Mindestbreite, damit
     * Seitentitel lesbar bleiben; die haengt an der Schriftgroesse, nicht an der Fensterbreite. Die
     * Oberflaeche begrenzt den Wert beim Laden zusaetzlich auf das aktuelle Fenster — sonst ist die
     * am grossen Monitor eingestellte Breite auf dem Notebook unbrauchbar.
     */
    private int wikiTreeWidth = 0;

    /**
     * Karte 937: Breite der Mail-Liste in Pixeln, {@code 0} = Vorgabe des Layouts.
     *
     * <p>Getrennt vom Wiki-Wert mit Absicht: Die beiden Ansichten haben nichts miteinander zu tun,
     * und ein gemeinsamer Wert wuerde beim Verschieben der einen die andere mitverstellen.
     */
    private int mailListWidth = 0;

    private String language = "de";

    private String user = "";

    /**
     * Returns the custom colors list, initializing it if null (XStream deserialization of old data).
     */
    public List<NamedColor> getCustomColors() {
        if (customColors == null) {
            customColors = new ArrayList<>();
        }
        return customColors;
    }

    /**
     * Returns the hidden colors set, initializing it if null (XStream deserialization of old data).
     */
    public Set<String> getHiddenColors() {
        if (hiddenColors == null) {
            hiddenColors = new HashSet<>();
        }
        return hiddenColors;
    }

    @Override
    public String getUniqueId() {
        return user;
    }

    @Override
    public void setUniqueId(String id) {
        user = id;
    }

    /**
     * A named custom color with a display name and hex value.
     */
    @Data
    public static class NamedColor implements Serializable {
        private String name;
        private String hex;

        public NamedColor() {}

        public NamedColor(String name, String hex) {
            this.name = name;
            this.hex = hex;
        }
    }
}

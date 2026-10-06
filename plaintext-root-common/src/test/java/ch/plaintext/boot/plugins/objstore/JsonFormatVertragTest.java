/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.objstore;

import ch.plaintext.boot.plugins.jsf.userprofile.ThemeColorProvider;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Karte 1423: JSON, das root speichert oder ausliefert, als Vertrag. Die Vertragsdateien stammen vom Stand
 * mit Jackson 2; nach der Umstellung muss die Ausgabe gleich bleiben, und alte Zeilen muessen lesbar sein.
 */
class JsonFormatVertragTest {

    /**
     * Vergleich gegen die Vertragsdatei. Fehlt sie, wird die heutige Ausgabe geschrieben und der Test
     * schlaegt fehl: die Datei muss einmal mit dem ALTEN Stand (Jackson 2) erzeugt und eingecheckt sein.
     */
    static void vergleiche(String datei, String ist) throws java.io.IOException {
        java.nio.file.Path p = java.nio.file.Path.of("src/test/resources/json-vertrag", datei);
        if (!java.nio.file.Files.exists(p)) {
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, ist);
            org.junit.jupiter.api.Assertions.fail("Vertragsdatei " + datei + " neu geschrieben - pruefen und einchecken");
        }
        org.junit.jupiter.api.Assertions.assertEquals(java.nio.file.Files.readString(p), ist,
                "Format von " + datei + " weicht vom Stand mit Jackson 2 ab");
    }

    private static UserPreference praeferenz() {
        UserPreference p = new UserPreference();
        p.setUniqueId("anna@example.ch");
        p.setDarkMode("dark");
        p.setCustomColor("#FF5733");
        p.getCustomColors().add(new UserPreference.NamedColor("Lachs", "#FA8072"));
        p.getHiddenColors().add("blue");
        p.getTabellenSpalten().put("useradmin", List.of("name", "mail"));
        return p;
    }

    @Test
    @DisplayName("UserPreference in der Datenbank-Spalte: byte-gleich zu Jackson 2, alte Zeile lesbar")
    void userPreference() throws Exception {
        SimpleStorableConverter c = new SimpleStorableConverter();
        vergleiche("userpreference.json", c.convertToDatabaseColumn(praeferenz()));
        Object zurueck = c.convertToEntityAttribute(Files.readString(Path.of("src/test/resources/json-vertrag/userpreference.json")));
        assertEquals(praeferenz(), zurueck);
    }

    @Test
    @DisplayName("Farbpalette fuer config.js: byte-gleich zu Jackson 2")
    void farbpalette() throws Exception {
        vergleiche("theme-colors.json", new ThemeColorProvider().getColorsJson());
    }
}

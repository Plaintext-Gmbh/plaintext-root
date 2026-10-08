/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 1438: the language guard against known texts — a German dummy must turn it red, otherwise a
 * green {@link PlaintextKatalogSpracheTest} would only prove that the guard sees nothing.
 */
class PlaintextKatalogSpracheFallTest {

    @Test
    @DisplayName("Deutsch erkannt: Umlaut, ß, zwei deutsche Wörter; ASCII-Deutsch (fuer, ueber) ebenso")
    void deutschErkannt() {
        assertNotNull(PlaintextKatalogSpracheTest.deutsch("Lesender Zugriff anderer Module auf die Tagessummen."));
        assertNotNull(PlaintextKatalogSpracheTest.deutsch("Schnittstelle fuer Auszahlungen."), "kurzer Text, ein Wort genuegt");
        assertNull(PlaintextKatalogSpracheTest.deutsch("Schliesst die Mahlzeit ab."), "bekannte Grenze: ohne Merkwort rutscht ein Satz durch");
        assertNotNull(PlaintextKatalogSpracheTest.deutsch("Service-Schnittstelle fuer Auszahlungen und Belege."));
        assertTrue(String.valueOf(PlaintextKatalogSpracheTest.deutsch("Grösse in Bytes")).contains("Umlaut"));
        assertTrue(String.valueOf(PlaintextKatalogSpracheTest.deutsch("Strasse, Gruß")).contains("ß"));
        assertTrue(String.valueOf(PlaintextKatalogSpracheTest.deutsch("Liefert den Kontakt oder null.")).contains("deutsche Wörter"));
    }

    @Test
    @DisplayName("Englisch grün, auch mit deutschem Namen in Code, Link oder Anführungszeichen")
    void englischGruen() {
        assertNull(PlaintextKatalogSpracheTest.deutsch("Interface for scheduled cron jobs in the Plaintext application."));
        assertNull(PlaintextKatalogSpracheTest.deutsch("Sends the mail."), "kurzer englischer Text");
        assertNull(PlaintextKatalogSpracheTest.deutsch("Read access for other modules to the daily totals of meals."));
        assertNull(PlaintextKatalogSpracheTest.deutsch("Turns a period picked in a drop-down (\"Aktuelle Woche\", \"Q3\") "
                + "into a from/to pair."));
        assertNull(PlaintextKatalogSpracheTest.deutsch("Implemented in {@link ch.plaintext.kalorien.IMahlzeitenAbfrage} and "
                + "{@code plaintext-z-kalenderhost}; see «Rechnungen → Einstellungen»."));
        assertNull(PlaintextKatalogSpracheTest.deutsch("Cut the image, die if the input is empty, then send it in an e-mail."), "die/in/an sind auch englisch");
        assertNull(PlaintextKatalogSpracheTest.deutsch(null));
        assertNull(PlaintextKatalogSpracheTest.deutsch("fuer(\"bild.vorschau\")"), "Methodenname im Beispiel ist Code");
        assertNull(PlaintextKatalogSpracheTest.deutsch("Returns ablage.liste of the store."), "Feld nach Punkt ist Code");
        assertNotNull(PlaintextKatalogSpracheTest.deutsch("fuer(x) liefert den Sidecar"), "Prosa neben Code bleibt geprüft");
    }

    @Test
    @DisplayName("Katalog-JSON: nur Prosa-Felder, mit Modul und Element in der Meldung; Escapes gelesen")
    void katalogGeprueft() {
        String json = """
                {"modul":"plaintext-z-test","schnittstellen":[{"name":"ch.x.IFoto","kurz":"IFoto",
                 "zweck":"Lesender Zugriff auf die Fotos.","herkunft":"im Modul","methoden":[
                 {"name":"alle","zweck":"Returns all photos.","parameter":[{"name":"fuer","typ":"java.lang.String"}]},
                 {"name":"eine","zweck":"Liefert das Foto \\u00fcber die Id.","parameter":[]}]}],
                 "umsetzungen":[{"klasse":"ch.x.FotoDienst","kurz":"FotoDienst","beschreibung":"Reads photos.",
                 "hinweise":["Only own photos","Nur eigene Fotos und keine fremden"],"beispiele":[]}]}
                """;
        List<PlaintextKatalogSpracheTest.Fund> f = PlaintextKatalogSpracheTest.pruefe(json);
        assertEquals(List.of("ch.x.IFoto", "eine", "ch.x.FotoDienst"), f.stream().map(PlaintextKatalogSpracheTest.Fund::element).toList());
        assertEquals(List.of("zweck", "zweck", "hinweise"), f.stream().map(PlaintextKatalogSpracheTest.Fund::schluessel).toList());
        assertTrue(f.get(1).grund().contains("Umlaut «ü»"), f.get(1).grund());
        assertTrue(f.getFirst().toString().startsWith("plaintext-z-test · ch.x.IFoto · zweck:"), f.getFirst().toString());
    }

    @Test
    @DisplayName("Positivkontrolle am Klassenpfad: der Wächter findet die deutsche Attrappe aus src/test/resources")
    void attrappeAmKlassenpfad() throws Exception {
        List<String> gefunden = new java.util.ArrayList<>();
        for (java.net.URL u : PlaintextKatalogSpracheTest.kataloge()) {
            try (var in = u.openStream()) {
                PlaintextKatalogSpracheTest.pruefe(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .forEach(x -> gefunden.add(x.modul() + "/" + x.element()));
            }
        }
        assertTrue(gefunden.contains("plaintext-attrappe/ch.plaintext.attrappe.IAttrappe"), "gefunden: " + gefunden);
    }
}

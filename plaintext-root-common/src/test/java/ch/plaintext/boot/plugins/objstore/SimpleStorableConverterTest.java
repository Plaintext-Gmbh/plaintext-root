/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.objstore;

import ch.plaintext.framework.XstreamBaseJPAConverter;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Converter schreibt JSON und liest JSON wie XML.
 * <p>
 * Der Test verwendet einen eigenen Typ statt einer Anwendungsklasse: der
 * Converter liegt seit der Auslagerung in plaintext-root-common, und ein Test
 * dort darf nicht auf das webapp-Modul zeigen.
 */
class SimpleStorableConverterTest {

    /** Testtyp - oeffentlich und nicht final, damit Jackson ihn erzeugen kann. */
    @Getter
    @Setter
    public static class TestPref implements SimpleStorable<TestPref> {
        private String uniqueId;
        private String darkMode;
        private List<String> farben = new ArrayList<>();
    }

    private SimpleStorableConverter converter;

    @BeforeEach
    void setUp() {
        converter = new SimpleStorableConverter();
    }

    private TestPref pref() {
        TestPref p = new TestPref();
        p.setUniqueId("test-user");
        p.setDarkMode("dark");
        p.getFarben().add("gruen");
        return p;
    }

    @Test
    @DisplayName("Geschrieben wird JSON, nicht mehr XML")
    void writesJson() {
        String written = converter.convertToDatabaseColumn(pref());

        assertNotNull(written);
        assertTrue(written.startsWith("{"), "JSON-Objekt");
        assertTrue(written.contains("TestPref"), "der konkrete Typ steht mit drin, sonst ist das Interface nicht rekonstruierbar");
        assertTrue(written.contains("test-user"));
        assertTrue(written.contains("dark"));
    }

    @Test
    @DisplayName("Was geschrieben wurde, kommt unveraendert zurueck")
    void jsonRoundtrip() {
        String written = converter.convertToDatabaseColumn(pref());

        SimpleStorable back = converter.convertToEntityAttribute(written);

        assertInstanceOf(TestPref.class, back);
        TestPref p = (TestPref) back;
        assertEquals("test-user", p.getUniqueId());
        assertEquals("dark", p.getDarkMode());
        assertEquals(List.of("gruen"), p.getFarben());
    }

    @Test
    @DisplayName("Altbestand im XStream-Format bleibt lesbar")
    void readsLegacyXml() {
        // Der eigentliche Zweck dieser Fassung: bestehende Spalten enthalten
        // XStream-XML. Ohne diesen Weg waeren sie nach dem Update unlesbar und
        // jede Installation braeuchte ein Migrationsskript.
        XstreamBaseJPAConverter<SimpleStorable> alt = new XstreamBaseJPAConverter<>();
        String xml = alt.convertToDatabaseColumn(pref());
        assertTrue(xml.startsWith("<"), "Vorbedingung: XStream schreibt XML");

        SimpleStorable back = converter.convertToEntityAttribute(xml);

        assertInstanceOf(TestPref.class, back);
        TestPref p = (TestPref) back;
        assertEquals("test-user", p.getUniqueId());
        assertEquals("dark", p.getDarkMode());
        assertEquals(List.of("gruen"), p.getFarben());
    }

    @Test
    @DisplayName("Ein einmal gelesener Altbestand wird als JSON zurueckgeschrieben")
    void legacyIsRewrittenAsJson() {
        // So wandert der Bestand ohne Migrationsskript hinueber: lesen, und beim
        // naechsten Speichern liegt JSON in der Spalte.
        XstreamBaseJPAConverter<SimpleStorable> alt = new XstreamBaseJPAConverter<>();
        String xml = alt.convertToDatabaseColumn(pref());

        SimpleStorable gelesen = converter.convertToEntityAttribute(xml);
        String neuGeschrieben = converter.convertToDatabaseColumn(gelesen);

        assertTrue(neuGeschrieben.contains("test-user"));
        assertTrue(neuGeschrieben.startsWith("{"), "jetzt JSON");
    }

    @Test
    @DisplayName("null und Leerstring ergeben null")
    void nullAndBlank() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(converter.convertToEntityAttribute(""));
        assertNull(converter.convertToEntityAttribute("   "));
    }

    @Test
    @DisplayName("Ein defekter Wert sprengt die Abfrage nicht")
    void brokenValueReturnsNull() {
        // Eine Ausnahme hier wuerde jede Abfrage reissen, die die Spalte laedt.
        assertNull(converter.convertToEntityAttribute("{kein gueltiges json"));
    }
}

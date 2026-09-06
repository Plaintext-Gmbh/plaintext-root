/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.security.ForbiddenClassException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XstreamBaseJPAConverterTest {

    private XstreamBaseJPAConverter<List<String>> converter;

    @BeforeEach
    void setUp() {
        converter = new XstreamBaseJPAConverter<>();
    }

    // -------------------------------------------------------------------------
    // convertToDatabaseColumn
    // -------------------------------------------------------------------------

    @Test
    void convertToDatabaseColumn_withList_returnsXml() {
        ArrayList<String> input = new ArrayList<>();
        input.add("tag1");
        input.add("tag2");
        input.add("tag3");
        String xml = converter.convertToDatabaseColumn(input);

        assertNotNull(xml);
        assertTrue(xml.contains("tag1"));
        assertTrue(xml.contains("tag2"));
        assertTrue(xml.contains("tag3"));
    }

    @Test
    void convertToDatabaseColumn_withNull_returnsXml() {
        // XStream will serialize null
        String xml = converter.convertToDatabaseColumn(null);
        assertNotNull(xml);
    }

    @Test
    void convertToDatabaseColumn_withEmptyList_returnsXml() {
        ArrayList<String> input = new ArrayList<>();
        String xml = converter.convertToDatabaseColumn(input);
        assertNotNull(xml);
    }

    // -------------------------------------------------------------------------
    // convertToEntityAttribute
    // -------------------------------------------------------------------------

    @Test
    void convertToEntityAttribute_withNull_returnsNull() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void convertToEntityAttribute_withEmptyString_returnsNull() {
        assertNull(converter.convertToEntityAttribute(""));
    }

    @Test
    void convertToEntityAttribute_roundTrip() {
        ArrayList<String> original = new ArrayList<>();
        original.add("alpha");
        original.add("beta");
        original.add("gamma");
        String xml = converter.convertToDatabaseColumn(original);

        @SuppressWarnings("unchecked")
        List<String> restored = converter.convertToEntityAttribute(xml);

        assertNotNull(restored);
        assertEquals(3, restored.size());
        assertEquals("alpha", restored.get(0));
        assertEquals("beta", restored.get(1));
        assertEquals("gamma", restored.get(2));
    }

    @Test
    void convertToEntityAttribute_invalidXml_returnsNullInsteadOfCommaSplit() {
        // Karte 1069 (S-04): der fruehere stille Rueckfall auf text.split(",") ist entfernt.
        // Ein Lesefehler liefert jetzt null (und loggt laut), wie die beiden anderen frueheren
        // Kopien dieser Klasse es schon taten -- statt eine manipulierte oder kaputte Spalte
        // klaglos in eine plausibel aussehende Liste zu verwandeln.
        @SuppressWarnings("unchecked")
        List<String> result = converter.convertToEntityAttribute("a,b,c");

        assertNull(result);
    }

    @Test
    void convertToEntityAttribute_singleValue_returnsNull() {
        @SuppressWarnings("unchecked")
        List<String> result = converter.convertToEntityAttribute("singlevalue");

        assertNull(result);
    }

    // -------------------------------------------------------------------------
    // Allowlist (Karte 1069, S-04): ch.** -> ch.plaintext.**
    // -------------------------------------------------------------------------

    @Test
    void allowlist_forbiddenClass_isRejected() {
        // Positivkontrolle fuer die Suchmethode selbst: vor S-04 liess die Allowlist "ch.**" jede
        // Klasse im Paket ch.qos.logback zu (Logback ist eine Laufzeitabhaengigkeit von Spring
        // Boot und liegt auf dem Klassenpfad jeder Anwendung).
        //
        // Der Bericht zu Karte 1069 nannte als Beispiel ch.qos.logback.core.db.JNDIConnectionSource
        // -- diese Klasse existiert in der hier eingesetzten Logback-Version 1.5.34 NICHT MEHR
        // (das Paket ch.qos.logback.core.db gibt es gar nicht, geprueft per `unzip -l` gegen das
        // Jar im lokalen Repository, Karte 1069, 06.09.2026). Ob dieselbe Klasse in einer
        // frueheren, noch unterstuetzten Logback-Version existierte, wurde nicht geprueft. Als
        // Ersatz hier eine tatsaechlich vorhandene ch.qos.logback-Klasse: sie zeigt genau dieselbe
        // Eigenschaft (frueher durch "ch.**" erlaubt, jetzt durch "ch.plaintext.**" abgewiesen),
        // ohne von einer konkreten, moeglicherweise nicht mehr vorhandenen Gadget-Klasse abzuhaengen.
        XStream xstream = XstreamBaseJPAConverter.createXStream();
        String bosartig = "<ch.qos.logback.core.util.JNDIUtil/>";

        assertThrows(ForbiddenClassException.class, () -> xstream.fromXML(bosartig));
    }

    @Test
    void allowlist_plaintextClass_staysReadable() {
        // Negativprobe zur vorigen Positivkontrolle: eine eigene ch.plaintext-Klasse bleibt
        // lesbar, die Verengung trifft nur ch.* ausserhalb von ch.plaintext.
        XStream xstream = XstreamBaseJPAConverter.createXStream();
        String eigen = xstream.toXML(new java.util.ArrayList<>(List.of("a", "b")));

        Object restored = xstream.fromXML(eigen);

        assertEquals(List.of("a", "b"), restored);
    }

    @Test
    void convertToEntityAttribute_forbiddenClassColumn_returnsNullNotThrows() {
        // Die aussen sichtbare Wirkung derselben Abwehr: convertToEntityAttribute faengt die
        // ForbiddenClassException wie jeden anderen Lesefehler ab und liefert null, statt den
        // Entity-Ladevorgang der ganzen Seite hart abzubrechen.
        @SuppressWarnings("unchecked")
        List<String> result = converter.convertToEntityAttribute(
                "<ch.qos.logback.core.util.JNDIUtil/>");

        assertNull(result);
    }
}

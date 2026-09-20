/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jede XML-Datei dieses Moduls laesst sich parsen — Karte 1312.
 *
 * <h2>Was das gekostet hat</h2>
 *
 * <p>Am 20.09.2026 stand in einem Kommentar von {@code frame.xhtml} der Name einer CSS-Variablen:
 * {@code --w-schrift-knopf}. Zwei Bindestriche <b>innerhalb</b> eines XML-Kommentars sind nicht
 * erlaubt. Der Bau war gruen, alle 141 Modultests waren gruen, und <b>jede</b> Watch-Seite
 * antwortete mit einer Fehlerseite:
 *
 * <pre>
 *   jakarta.faces.view.facelets.FaceletException: Error Parsing …/watch/frame.xhtml:
 *       Error Traced[line: 106] Zeichenfolge "--" ist in Kommentaren nicht zulaessig.
 * </pre>
 *
 * <p>Gefunden hat es erst ein Playwright-Lauf, der die Anwendung hochfaehrt und einen Browser
 * davorstellt — fuenf Minuten je Durchgang. Derselbe Fund kostet hier Millisekunden. Dieselbe
 * Fehlerklasse hat das Haus schon einmal bezahlt, damals in einer {@code pom.xml}, die dadurch
 * unparsebar wurde.
 *
 * <p><b>Was der Test NICHT leistet:</b> er prueft die Wohlgeformtheit, nicht den Sinn. Ein
 * {@code w:aktion}, das es in keiner Taglib gibt, ist wohlgeformt und faellt hier nicht auf —
 * dafuer ist {@code PlaintextTaglibVertragTest} zustaendig, und im Zweifel der Seitendurchgang.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchWohlgeformtVertragTest {

    /** Ein Kommentar mit zwei Bindestrichen darin — genau der Fehler vom 20.09.2026. */
    private static final String KAPUTT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <a><!-- die Variable --w-schrift-knopf --></a>
            """;

    private static Path wurzel() {
        Path p = Path.of("src/main/resources");
        return Files.isDirectory(p) ? p : Path.of("plaintext-root-watch").resolve(p);
    }

    private static DocumentBuilder parser() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // Keine externen Zugriffe: ein Parser, der beim Bauen ins Netz greift, ist kein Test.
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        return b;
    }

    private static List<Path> dateien() throws IOException {
        Path wurzel = wurzel();
        assertTrue(Files.isDirectory(wurzel), "Nicht gefunden: " + wurzel.toAbsolutePath());
        try (Stream<Path> s = Files.walk(wurzel)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".xhtml") || n.endsWith(".xml");
                    })
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("Jede .xhtml und .xml des Watch-Moduls ist wohlgeformtes XML")
    void allesLaesstSichParsen() throws Exception {
        DocumentBuilder parser = parser();
        List<Path> dateien = dateien();

        // Positivkontrolle zuerst: findet dieser Aufbau den Fehler ueberhaupt? Ohne sie waere
        // der Test auch dann gruen, wenn der Parser jede Eingabe schluckt.
        assertThrows(SAXException.class,
                () -> parser().parse(new InputSource(new StringReader(KAPUTT))),
                "Der Parser nimmt einen Kommentar mit zwei Bindestrichen an — dann belegt ein "
                        + "gruener Lauf unten nichts.");

        assertTrue(dateien.size() >= 4,
                "Nur " + dateien.size() + " Dateien gefunden — das Muster passt nicht mehr.");

        List<String> fehler = new ArrayList<>();
        for (Path datei : dateien) {
            try {
                parser.parse(datei.toFile());
            } catch (SAXException | IOException e) {
                fehler.add(wurzel().relativize(datei) + ": " + e.getMessage());
            }
        }
        assertTrue(fehler.isEmpty(),
                () -> "Diese Dateien sind kein gueltiges XML. Der Bau bleibt gruen und JEDE Seite, "
                        + "die sie einbindet, antwortet zur Laufzeit mit einer Fehlerseite:\n  "
                        + String.join("\n  ", fehler));
    }

    @Test
    @DisplayName("Positivkontrolle: es gibt ueberhaupt Dateien mit Kommentaren zu pruefen")
    void esGibtKommentareZuPruefen() throws IOException {
        boolean mitKommentar = false;
        for (Path datei : dateien()) {
            if (lies(datei).contains("<!--")) {
                mitKommentar = true;
                break;
            }
        }
        assertTrue(mitKommentar, "Keine einzige Datei mit einem XML-Kommentar — dann kann der "
                + "Test oben den Fehler vom 20.09.2026 gar nicht mehr treffen.");
        assertFalse(dateien().isEmpty(), "keine Dateien gefunden");
    }

    private static String lies(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

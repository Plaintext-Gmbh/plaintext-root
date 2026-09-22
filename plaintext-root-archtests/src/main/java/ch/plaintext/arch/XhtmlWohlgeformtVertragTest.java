/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1029 (uebertragen aus Karte 1012, plaintext-app): jede Facelets-Seite des Reactors ist
 * wohlgeformtes XML.
 *
 * <p><b>Die Fehlerklasse, und sie stand am 01.09.2026 in PROD.</b> Ein erklaerender Kommentar in
 * {@code auszahlungeinstellungen.xhtml} (plaintext-app) enthielt einen Gedankenstrich als
 * {@code --}. XML verbietet {@code --} innerhalb eines Kommentars; der Facelets-Compiler liest
 * die Seite mit einem SAX-Parser, also endete <i>jeder</i> Aufruf der Seite mit
 * {@code SAXParseException} &rarr; HTTP 500. Sie war ueber 26 Releases kaputt, und kein Test hat
 * es bemerkt: Modultests arbeiten auf Java und Datenbank, und eine rollengeschuetzte Seite wird
 * von keinem Statuscode-Durchgang ohne Anmeldung geladen — der sieht nur die Umleitung aufs Login.
 *
 * <p><b>Karte 1298: eine Fassung statt vier.</b> Bis zum 22.09.2026 lag dieser Test als Kopie in
 * root, app, schuetu und iot; guild und fwtool hatten ihn nicht. Die Kopien suchten nur eine
 * Modulebene unter der Repo-Wurzel ({@code Files.list}); diese Fassung sucht ueber
 * {@link ReactorLayout#sourceRoots(String)} in jedem, auch geschachtelten Modul. Die Untergrenze
 * der Dateisuche bleibt je Repo die gemessene (root 40, app 50, schuetu 35, iot 2 — siehe
 * {@link #MINDESTENS_JE_REACTOR}); die kleinste fuer alle zu nehmen, haette die schwaechste Kopie
 * gewinnen lassen. Der app-eigene Fall „die reparierte Seite ist wohlgeformt" ist im
 * Hauptdurchgang enthalten: {@code auszahlungeinstellungen.xhtml} liegt in app unter
 * {@code src/main/resources} und wird wie jede andere Seite geparst.
 *
 * <p><b>Build-Cache.</b> Der Test laeuft im Webapp-Modul des jeweiligen Repos (dort wird dieses Jar
 * gescannt), nicht im Modul {@code plaintext-root-archtests} selbst. Das ist Absicht: die Webapp
 * haengt an allen Modulen, aendert sich irgendwo eine Seite, aendert sich ihr Cache-Schluessel mit.
 * In archtests selbst liefe er bei einer XHTML-Aenderung in einem anderen Modul gar nicht
 * (gemessen am 04.09.2026: „BUILD SUCCESS" ohne eine einzige ausgefuehrte Testklasse).
 *
 * <p><b>Was der Test NICHT leistet:</b> Wohlgeformtheit ist keine Lauffaehigkeit. Eine unbekannte
 * Komponente, ein nicht aufloesbarer EL-Ausdruck oder ein ins Leere zeigendes
 * {@code update}-Attribut sind wohlgeformtes XML — dafuer ist der Seitendurchgang zustaendig.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@DisplayName("Karte 1029: jede XHTML-Seite des Reactors ist wohlgeformtes XML")
class XhtmlWohlgeformtVertragTest {

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * Begruendete Ausnahmen als Reactor-relativer Pfad. <b>Heute leer</b>, und das ist der Punkt:
     * eine Seite, die sich nicht parsen laesst, ist im Betrieb HTTP 500. Es gibt keinen guten Grund.
     */
    private static final Set<String> AUSNAHMEN = Set.of();

    /**
     * Untergrenze der gefundenen XHTML-Dateien je Reactor (Schluessel = artifactId der Wurzel-pom).
     * Die Werte der vier Kopien uebernommen (dort als {@code > n} formuliert, hier {@code >= n+1});
     * guild und fwtool am 22.09.2026 gezaehlt (21 bzw. 6 Dateien) und mit Abstand darunter gesetzt.
     */
    static final Map<String, Integer> MINDESTENS_JE_REACTOR = Map.of(
            "plaintext-root-parent", 41,
            "plaintext-parent", 51,
            "plaintext-guild-parent", 15,
            "plaintext-schuetu-parent", 36,
            "plaintext-iot-parent", 3,
            "plaintext-fwtool-parent", 4);

    /** Fuer einen unbekannten Reactor: der kleinste gemessene Wert. */
    private static final int MINDESTENS_SONST = 3;

    private static Path repoWurzel;
    private static List<Path> xhtmls;

    @BeforeAll
    static void quellenEinlesen() {
        repoWurzel = ReactorLayout.repoRoot();
        assertNotNull(repoWurzel, "Kein Reactor oberhalb von " + ReactorLayout.start() + " gefunden.");
        xhtmls = ReactorLayout.sourceRoots(RESOURCES_SUFFIX).stream()
                .flatMap(XhtmlWohlgeformtVertragTest::xhtmlDateien)
                .distinct()
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("jede XHTML-Datei laesst sich als XML parsen")
    void alleSeitenSindWohlgeformt() {
        List<String> funde = new ArrayList<>();
        for (Path datei : xhtmls) {
            String relativ = ReactorLayout.relativ(datei);
            if (AUSNAHMEN.contains(relativ)) {
                continue;
            }
            String fehler = parseFehler(lies(datei));
            if (fehler != null) {
                funde.add(relativ + " -> " + fehler);
            }
        }
        assertTrue(funde.isEmpty(),
                "Nicht wohlgeformte Facelets-Seite gefunden. Der Facelets-Compiler liest sie mit "
                        + "einem XML-Parser — die Seite ist im Betrieb HTTP 500 (Karte 1012/1029):\n  "
                        + String.join("\n  ", funde));
    }

    /**
     * Kontrolle, ohne die ein gruener Lauf oben nichts belegt: greift die Dateisuche ueberhaupt?
     * Ein verrutschter Pfad ginge sonst als „alles sauber" durch — genau der Fehler, an dem der
     * erste Anlauf zu Karte 1012 gescheitert ist.
     */
    @Test
    @DisplayName("die Suche erfasst die XHTML-Dateien dieses Reactors")
    void dieSucheGreift() {
        int mindestens = ReactorLayout.mindestensFuerDiesenReactor(MINDESTENS_JE_REACTOR, MINDESTENS_SONST);
        assertTrue(xhtmls.size() >= mindestens,
                "Nur " + xhtmls.size() + " XHTML-Dateien unter " + repoWurzel + " gefunden, festgehalten "
                        + "sind fuer " + ReactorLayout.reactorArtifactId() + " mindestens " + mindestens
                        + " — die Suche greift ins Leere und ihr Ergebnis ist wertlos.");
    }

    /**
     * Positivkontrolle mit dem <b>woertlichen</b> Kommentar, der die Auszahlungs-Einstellungen
     * lahmgelegt hat. Schlaegt sie nicht an, prueft der Test oben nichts.
     */
    @Test
    @DisplayName("Positivkontrolle: der Kommentar, der PROD lahmgelegt hat, faellt auf")
    void positivkontrolleGedankenstrichImKommentar() {
        String kaputt = """
                <ui:composition xmlns:ui="jakarta.faces.facelets">
                    <!-- Speichern steht ganz unten (Auftrag Daniel, 29.08.2026): Es sichert die
                         Felder ueber der Liste, stand aber mitten auf der Seite -- wer nach unten
                         scrollte, sah zuerst die Verwaltungsbloecke. -->
                </ui:composition>
                """;
        String fehler = parseFehler(kaputt);
        assertNotNull(fehler, "Der Kommentar mit '--' muss auffallen, sonst prueft dieser Test nichts.");
        assertTrue(fehler.contains("--"), "Erwartet wird die Meldung zum '--': " + fehler);
    }

    /** Zweite Positivkontrolle: ein nicht geschlossenes Tag ist ebenfalls HTTP 500. */
    @Test
    @DisplayName("Positivkontrolle: ein nicht geschlossenes Tag faellt auf")
    void positivkontrolleOffenesTag() {
        assertNotNull(parseFehler("<ui:composition xmlns:ui=\"jakarta.faces.facelets\"><h:x>"));
    }

    /**
     * Negativkontrolle: eine gewoehnliche Seite mit DOCTYPE, mehreren Namensraeumen, EL und
     * maskiertem Kaufmanns-Und ist <i>kein</i> Befund. Ohne diese Abgrenzung waere der Test
     * unbrauchbar — er wuerde alles melden.
     */
    @Test
    @DisplayName("Negativkontrolle: eine gewoehnliche Seite ist kein Befund")
    void negativkontrolle() {
        String gut = """
                <!DOCTYPE html>
                <ui:composition xmlns:ui="jakarta.faces.facelets"
                                xmlns:h="jakarta.faces.html"
                                xmlns:p="primefaces"
                                template="/template.xhtml">
                    <ui:define name="content">
                        <!-- ein ganz normaler Hinweis, mit Gedankenstrich als Semikolon; so geht es -->
                        <h:form id="fm">
                            <h:outputText value="Soll &amp; Haben #{bean.titel}"/>
                            <p:commandButton value="Speichern" action="#{bean.save()}" update="fm"/>
                        </h:form>
                    </ui:define>
                </ui:composition>
                """;
        assertNull(parseFehler(gut));
    }

    // ------------------------------------------------------------------------------------------

    /** Liefert die Fehlermeldung des XML-Parsers, oder {@code null}, wenn die Seite wohlgeformt ist. */
    static String parseFehler(String inhalt) {
        try {
            DocumentBuilder builder = builder();
            builder.parse(new InputSource(new StringReader(inhalt)));
            return null;
        } catch (SAXParseException e) {
            return "Zeile " + e.getLineNumber() + ", Spalte " + e.getColumnNumber() + ": " + e.getMessage();
        } catch (SAXException | IOException e) {
            return e.getMessage();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML-Parser nicht konfigurierbar", e);
        }
    }

    /**
     * Parser ohne Netzzugriff: der {@code <!DOCTYPE html>} der Seiten darf keine DTD nachladen
     * (das waere sowohl langsam als auch ein XXE-Einfallstor), soll aber auch nicht stoeren.
     */
    private static DocumentBuilder builder() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setValidating(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder builder = f.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) ->
                new InputSource(new ByteArrayInputStream(new byte[0])));
        // Der Standard-Handler schreibt Fehler zusaetzlich nach stderr; wir werten die Ausnahme aus.
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return builder;
    }

    private static Stream<Path> xhtmlDateien(Path wurzel) {
        try (Stream<Path> pfade = Files.walk(wurzel)) {
            return pfade.filter(p -> p.toString().endsWith(".xhtml")).map(p -> p.toAbsolutePath().normalize())
                    .toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException("Dateien unter " + wurzel + " nicht lesbar", e);
        }
    }

    private static String lies(Path datei) {
        try {
            return Files.readString(datei, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Datei " + datei + " nicht lesbar", e);
        }
    }
}

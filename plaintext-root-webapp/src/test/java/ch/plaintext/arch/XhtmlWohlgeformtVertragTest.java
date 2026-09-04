/*
 * Copyright (C) plaintext.ch, 2026.
 */
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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1029 (uebertragen aus Karte 1012, plaintext-app): jede Facelets-Seite dieses Repos ist
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
 * <p><b>Warum derselbe Test hier steht.</b> root liefert 53 XHTML-Dateien aus, die in <i>jeder</i>
 * der fuenf Anwendungen landen. Ein {@code --} in einer davon nimmt nicht eine Seite einer
 * Anwendung mit, sondern dieselbe Seite in allen. Der statische Durchgang vom 01.09.2026 hat root
 * sauber gefunden — dieser Test haelt das fest, statt es zu glauben.
 *
 * <p><b>Warum er in der Webapp liegt und nicht in {@code plaintext-root-archtests}.</b> root baut
 * mit dem Maven-Build-Cache. Ein Modul, dessen eigene Dateien sich nicht geaendert haben, wird
 * aus dem Cache restauriert — <b>seine Tests laufen dann gar nicht</b>. In {@code archtests}
 * (haengt nur an {@code plaintext-root-common}) waere dieser Test damit genau in dem Fall stumm,
 * fuer den es ihn gibt: eine Aenderung an einer XHTML-Datei in einem <i>anderen</i> Modul.
 * Gemessen am 04.09.2026 — ein absichtlich kaputt gemachtes {@code admin-api-token.xhtml} ergab
 * in archtests „BUILD SUCCESS" ohne eine einzige ausgefuehrte Testklasse. Die Webapp haengt an
 * allen 24 Modulen; aendert sich irgendwo eine Seite, aendert sich ihr Cache-Schluessel mit.
 *
 * <p><b>Was der Test NICHT leistet:</b> Wohlgeformtheit ist keine Lauffaehigkeit. Eine unbekannte
 * Komponente, ein nicht aufloesbarer EL-Ausdruck oder ein ins Leere zeigendes
 * {@code update}-Attribut sind wohlgeformtes XML — dafuer ist der Seitendurchgang zustaendig.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@DisplayName("Karte 1029: jede XHTML-Seite von root ist wohlgeformtes XML")
class XhtmlWohlgeformtVertragTest {

    /**
     * Begruendete Ausnahmen als Repo-relativer Pfad. <b>Heute leer</b>, und das ist der Punkt: eine
     * Seite, die sich nicht parsen laesst, ist im Betrieb HTTP 500. Es gibt keinen guten Grund.
     */
    private static final Set<String> AUSNAHMEN = Set.of();

    /**
     * Untergrenze fuer die Dateisuche. root hatte am 04.09.2026 <b>53</b> XHTML-Dateien; die
     * Grenze liegt bewusst darunter, damit ein neues Modul den Test nicht rot faerbt, aber weit
     * genug oben, dass ein verrutschter Pfad auffaellt.
     */
    private static final int MINDESTENS = 40;

    private static Path repoWurzel;
    private static List<Path> xhtmls;

    @BeforeAll
    static void quellenEinlesen() throws IOException {
        repoWurzel = findeRepoWurzel();
        try (Stream<Path> module = Files.list(repoWurzel)) {
            xhtmls = module
                    .map(modul -> modul.resolve("src/main/resources"))
                    .filter(Files::isDirectory)
                    .flatMap(XhtmlWohlgeformtVertragTest::xhtmlDateien)
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("jede XHTML-Datei laesst sich als XML parsen")
    void alleSeitenSindWohlgeformt() {
        List<String> funde = new ArrayList<>();
        for (Path datei : xhtmls) {
            String relativ = repoWurzel.relativize(datei).toString();
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
    @DisplayName("die Suche erfasst die XHTML-Dateien dieses Repos")
    void dieSucheGreift() {
        assertTrue(xhtmls.size() > MINDESTENS,
                "Nur " + xhtmls.size() + " XHTML-Dateien unter " + repoWurzel
                        + " gefunden — die Suche greift ins Leere und ihr Ergebnis ist wertlos.");
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
    private static String parseFehler(String inhalt) {
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
            return pfade.filter(p -> p.toString().endsWith(".xhtml")).toList().stream();
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

    private static Path findeRepoWurzel() {
        Path kandidat = Paths.get("").toAbsolutePath();
        while (kandidat != null) {
            if (Files.isDirectory(kandidat.resolve("plaintext-root-webapp"))
                    && Files.isRegularFile(kandidat.resolve("pom.xml"))) {
                return kandidat;
            }
            kandidat = kandidat.getParent();
        }
        throw new IllegalStateException(
                "Repo-Wurzel nicht gefunden ab " + Paths.get("").toAbsolutePath());
    }
}

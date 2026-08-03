/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.webapp;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Karte 502 (Ursache belegt in Karte 430): Kein woertliches CDATA-Ende innerhalb eines
 * {@code h:form} — geprueft ueber ALLE Module dieses Repositories.
 *
 * <p><b>Der Defekt.</b> Jede PrimeFaces-Antwort verpackt das aktualisierte Formular in
 * {@code <update id="…"><![CDATA[ … ]]></update>}. Steht im Formular irgendwo die Zeichenfolge
 * {@code ]]>} — typischerweise am Ende eines Inline-Scripts, das im Facelet als CDATA-Block
 * geschrieben ist —, dann beendet sie diese aeussere Sektion vorzeitig. Der XML-Parser des
 * Browsers bricht ab, PrimeFaces verwirft die <b>komplette</b> Antwort.</p>
 *
 * <p><b>Warum ein Test und nicht Sorgfalt.</b> Der Fehler ist von aussen unsichtbar: Der Server
 * rechnet richtig, antwortet mit HTTP 200 und liefert den vollstaendigen Inhalt; im Serverlog
 * steht nichts, auf der Browser-Konsole steht nichts. PrimeFaces meldet ihn nur ueber das
 * jQuery-Ereignis {@code pfAjaxError}, das niemand abhoert. Sichtbar ist allein, dass der Klick
 * nichts tut. Im Wiki hat das drei Diagnoserunden ueber zwei Tage gekostet (Karte 430).</p>
 *
 * <p><b>Warum repositoryweit.</b> {@code includes/config.xhtml} liegt im root-Template und steckt
 * damit in jeder Seite jeder Anwendung. Ein Test, der nur das eigene Modul prueft, haette genau
 * die Datei mit der groessten Reichweite nicht gesehen.</p>
 *
 * <p>Abhilfe an einer Fundstelle: das Inline-Script in eine eigene {@code .js}-Datei verschieben
 * (Muster {@code plaintext-layout/js/config.js}, {@code plaintext-layout/js/layout.js}) oder —
 * wenn es ohnehin nur globale Listener registriert — vor das Formular ziehen.</p>
 */
class AjaxAntwortLesbarTest {

    private static final String CDATA_ENDE = "]" + "]>";

    private static final Pattern FORM_START = Pattern.compile("<h:form\\b");
    private static final Pattern FORM_ENDE = Pattern.compile("</h:form>");

    @Test
    void keinCdataEndeInnerhalbEinesFormulars() throws IOException {
        List<String> verstoesse = new ArrayList<>();
        List<Path> dateien = facelets();
        for (Path datei : dateien) {
            String text = Files.readString(datei, StandardCharsets.UTF_8);
            for (int pos : cdataEndenInFormular(text)) {
                verstoesse.add(repoWurzel().relativize(datei) + ":" + zeileVon(text, pos));
            }
        }
        assertTrue(dateien.size() >= 20,
                "Es wurden nur " + dateien.size() + " Facelets gefunden — vermutlich sucht der Test "
                        + "an der falschen Stelle (" + repoWurzel() + "). Ein gruener Lauf waere wertlos.");
        assertTrue(verstoesse.isEmpty(),
                "Diese Stellen zerstoeren jede Ajax-Antwort ihrer Seite:\n  "
                        + String.join("\n  ", verstoesse)
                        + "\n\nEin woertliches CDATA-Ende innerhalb von h:form beendet die CDATA-Sektion "
                        + "des <update>-Elements vorzeitig; der Browser verwirft die komplette Antwort, "
                        + "ohne dass Server oder Konsole etwas melden. Abhilfe: Inline-Script in eine "
                        + "eigene .js-Datei verschieben, oder — wenn es nur globale Listener registriert "
                        + "— vor das Formular ziehen (Karte 502).");
    }

    /**
     * Gegenprobe zur Suche selbst. Ohne diesen Fall waere der Test oben auch dann gruen, wenn
     * {@link #cdataEndenInFormular} grundsaetzlich nichts findet.
     */
    @Test
    void dieSucheFindetDenFehlerUeberhaupt() {
        String schlecht = "<h:form id=\"fm\">\n  <script>//<![CDATA[\n  var a = 1 < 2;\n  //" + CDATA_ENDE
                + "</script>\n</h:form>";
        assertEquals(1, cdataEndenInFormular(schlecht).size(),
                "Die Suche uebersieht ein CDATA-Ende im Formular — dann sagt der Test oben nichts aus.");

        String harmlos = "<script>//<![CDATA[\n var a = 1;\n //" + CDATA_ENDE + "</script>\n"
                + "<h:form id=\"fm\">ohne Script</h:form>";
        assertTrue(cdataEndenInFormular(harmlos).isEmpty(),
                "Vor dem Formular ist ein CDATA-Ende unkritisch — hier darf nichts anschlagen. "
                        + "Genau darauf beruht die Abhilfe in myuser.xhtml und cron.xhtml.");
    }

    /** Belegt den Mechanismus am XML-Parser, statt ihn nur zu behaupten. */
    @Test
    void einCdataEndeImInhaltMachtDieAntwortUnlesbar() {
        String kaputt = huelle("<form id=\"fm\"><script>var x = 1;" + CDATA_ENDE + "</script></form>");
        SAXParseException e = assertThrows(SAXParseException.class, () -> parse(kaputt));
        assertTrue(e.getMessage().toLowerCase().contains("script")
                        || e.getMessage().toLowerCase().contains("update")
                        || e.getMessage().toLowerCase().contains("element"),
                "Erwartet wurde ein Tag-Mismatch, gemeldet wurde: " + e.getMessage());

        String gesund = huelle("<form id=\"fm\"><script src=\"config.js\"></script></form>");
        try {
            parse(gesund);
        } catch (Exception ex) {
            fail("Die reparierte Antwort muss lesbar sein, war sie aber nicht: " + ex);
        }
    }

    // ── Hilfsmittel ──────────────────────────────────────────

    private static String huelle(String inhalt) {
        return "<partial-response><changes><update id=\"fm\"><![CDATA[" + inhalt + CDATA_ENDE
                + "</update></changes></partial-response>";
    }

    /**
     * Wurzel des Repositories. Surefire startet im Modulverzeichnis; von dort wird nach oben
     * gesucht, bis das Sammel-POM gefunden ist. Wird es nicht gefunden, faellt der Test aus —
     * ein stillschweigend leerer Lauf waere schlimmer als ein roter.
     */
    private static Path repoWurzel() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            Path pom = p.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    String t = Files.readString(pom, StandardCharsets.UTF_8);
                    if (t.contains("<modules>")) {
                        return p;
                    }
                } catch (IOException ignored) {
                    // weiter nach oben
                }
            }
        }
        throw new IllegalStateException(
                "Sammel-POM nicht gefunden — der Test weiss nicht, wo das Repository beginnt. "
                        + "Startverzeichnis war " + Path.of("").toAbsolutePath());
    }

    private static List<Path> facelets() throws IOException {
        try (Stream<Path> s = Files.walk(repoWurzel())) {
            return s.filter(p -> p.toString().endsWith(".xhtml"))
                    .filter(p -> p.toString().contains("src" + java.io.File.separator + "main"))
                    .filter(p -> !p.toString().contains(java.io.File.separator + "target"
                            + java.io.File.separator))
                    .toList();
        }
    }

    /** Positionen aller {@code ]]>} , die innerhalb eines {@code h:form}-Bereichs liegen. */
    private static List<Integer> cdataEndenInFormular(String text) {
        List<int[]> bereiche = new ArrayList<>();
        Matcher start = FORM_START.matcher(text);
        while (start.find()) {
            Matcher ende = FORM_ENDE.matcher(text);
            int e = ende.find(start.end()) ? ende.start() : text.length();
            bereiche.add(new int[]{start.start(), e});
        }
        List<Integer> treffer = new ArrayList<>();
        int i = text.indexOf(CDATA_ENDE);
        while (i >= 0) {
            for (int[] b : bereiche) {
                if (i > b[0] && i < b[1]) {
                    treffer.add(i);
                    break;
                }
            }
            i = text.indexOf(CDATA_ENDE, i + 1);
        }
        return treffer;
    }

    private static int zeileVon(String text, int pos) {
        return (int) text.substring(0, pos).chars().filter(c -> c == '\n').count() + 1;
    }

    private static void parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}

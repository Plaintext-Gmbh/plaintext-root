/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drei Regeln fuer Inline-Scripts in Facelets — geteilt, weil jede von ihnen schon einmal einen
 * ganzen Tag gekostet hat und keine von ihnen sich von aussen bemerkbar macht.
 *
 * <p>Der Test liegt in {@code src/main/java} des Moduls {@code plaintext-root-archtests} und wandert
 * damit ins publizierte Jar. Consumer (app, iot, schuetu, guild) lassen ihn ueber Surefire
 * {@code <dependenciesToScan>} gegen ihre eigenen Facelets laufen — die Regel steht einmal,
 * nicht fuenfmal. Gemessen am 23.08.2026 sind alle fuenf Repositories sauber.</p>
 *
 * <p><b>Verhaeltnis zu den Kopien.</b> Er loest {@code ch.plaintext.webapp.AjaxAntwortLesbarTest}
 * in plaintext-root-webapp ab, der nur Regel 1 kannte: innerhalb <b>eines</b> Repositories, das in
 * einem Zug gebaut und zurueckgedreht wird, ist eine zweite Kopie derselben Regel nur die Stelle,
 * an der die beiden auseinanderlaufen. Die Fassung in plaintext-app bleibt bewusst bestehen —
 * dort gilt die Begruendung aus {@code AjaxZielAufloesbarTest}: app wird getrennt gebaut und
 * getrennt zurueckgedreht, und eine Regel, die mit einem root-Rollback verschwindet, ist an dem
 * Tag weg, an dem man sie braucht.</p>
 *
 * <h2>Regel 1 — kein woertliches CDATA-Ende innerhalb von {@code h:form} (Karten 430, 502)</h2>
 * <p>Jede PrimeFaces-Antwort verpackt das aktualisierte Formular in
 * {@code <update id="…"><![CDATA[ … ]]></update>}. Steht im Formular irgendwo die Zeichenfolge
 * {@code ]]>} — typischerweise am Ende eines Inline-Scripts —, beendet sie diese aeussere Sektion
 * vorzeitig. Der XML-Parser des Browsers bricht ab, PrimeFaces verwirft die <b>komplette</b>
 * Antwort und meldet das nur ueber das jQuery-Ereignis {@code pfAjaxError}, das niemand abhoert.</p>
 *
 * <h2>Regel 2 — ein {@code script}-Block ohne CDATA muss wohlgeformtes XML sein (Karte 502)</h2>
 * <p>Facelets parst die Datei sonst gar nicht mehr, und die Seite liefert HTTP 500. Gemessen wird
 * das hier am XML-Parser selbst statt an einer Zeichenliste: {@code &amp;&amp;} ist in einem Block
 * ohne CDATA <b>richtig</b> (der Parser loest es zu {@code &&} auf, so steht es in root's
 * topbar.xhtml), ein rohes {@code &&} oder {@code <} dagegen falsch. Eine Suche nach dem Zeichen
 * {@code &} wuerde beides gleich behandeln und die richtige Schreibweise anmahnen.</p>
 *
 * <h2>Regel 3 — keine XML-Entity innerhalb einer CDATA-Sektion (Karte 502, dritter Anlauf)</h2>
 * <p>Die Umkehrung von Regel 2: In einer CDATA-Sektion loest der Parser Entities <b>nicht</b> auf.
 * Wer dort {@code &amp;amp;&amp;amp;} statt {@code &&} schreibt, liefert dem Browser die Entity
 * woertlich aus; der bricht das ganze Script mit einem SyntaxError ab. Genau daran ist der
 * Kontakt-Deep-Link aus der globalen Suche gescheitert: HTTP 200, kein Serverlog, sichtbar war
 * allein, dass der Dialog nicht aufging. Ausgenommen ist die Entity, die <b>als Zeichenkette</b>
 * dasteht ({@code '&amp;amp;'}) — eine Escaping-Tabelle meint genau das und ist richtig.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextAjaxAntwortLesbarTest {

    private static final String CDATA_START = "<!" + "[CDATA[";
    private static final String CDATA_ENDE = "]" + "]>";

    /** XML-Entities, die innerhalb einer CDATA-Sektion nichts verloren haben. */
    private static final Pattern ENTITY = Pattern.compile("&(amp|lt|gt|quot|apos|#\\d+);");

    private static final Pattern FORM_START = Pattern.compile("<h:form\\b");
    private static final Pattern FORM_ENDE = Pattern.compile("</h:form>");
    private static final Pattern SCRIPT_START = Pattern.compile("<script\\b[^>]*>");

    /** Verzeichnisse, in die der Scan gar nicht erst absteigt. */
    private static final Set<String> UNINTERESSANT = Set.of("target", "node_modules", ".git");

    /** Einmal je Lauf gesucht — alle drei Regeln arbeiten auf derselben Liste. */
    private static List<Path> FACELETS;

    // ── Regel 1 ──────────────────────────────────────────────

    @Test
    void keinCdataEndeInnerhalbEinesFormulars() throws IOException {
        List<String> verstoesse = new ArrayList<>();
        for (Path datei : facelets()) {
            String text = lies(datei);
            for (int pos : cdataEndenInFormular(text)) {
                verstoesse.add(anzeige(datei) + ":" + zeileVon(text, pos));
            }
        }
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
     * Gegenprobe zur Suche selbst. Ohne diesen Fall waere die Regel oben auch dann gruen, wenn
     * {@link #cdataEndenInFormular} grundsaetzlich nichts findet — und genau das waere in einem
     * geteilten Test die gefaehrlichste Variante: fuenf Repositories haetten dann eine Zusicherung,
     * die nichts zusichert.
     */
    @Test
    void dieSucheFindetDenFehlerUeberhaupt() {
        String schlecht = "<h:form id=\"fm\">\n  <script>//" + CDATA_START + "\n  var a = 1 < 2;\n  //"
                + CDATA_ENDE + "</script>\n</h:form>";
        assertEquals(1, cdataEndenInFormular(schlecht).size(),
                "Die Suche uebersieht ein CDATA-Ende im Formular — dann sagt die Regel oben nichts aus.");

        String harmlos = "<script>//" + CDATA_START + "\n var a = 1;\n //" + CDATA_ENDE + "</script>\n"
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

    // ── Regel 2 ──────────────────────────────────────────────

    @Test
    void scriptOhneCdataIstWohlgeformtesXml() throws IOException {
        List<String> verstoesse = new ArrayList<>();
        for (Path datei : facelets()) {
            String text = lies(datei);
            for (int[] block : scriptBloeckeOhneCdata(text)) {
                String meldung = parserFehler(text.substring(block[0], block[1]));
                if (meldung != null) {
                    verstoesse.add(anzeige(datei) + ":" + zeileVon(text, block[0]) + " — " + meldung);
                }
            }
        }
        assertTrue(verstoesse.isEmpty(),
                "Diese script-Bloecke haben keine CDATA-Sektion und sind kein wohlgeformtes XML:\n  "
                        + String.join("\n  ", verstoesse)
                        + "\n\nFacelets parst die Datei dann nicht mehr und die Seite liefert HTTP 500 — "
                        + "auch wenn die Rohzeichen nur in einem Kommentar stehen. Entweder die Zeichen "
                        + "als Entity schreiben (&amp;amp;&amp;amp; statt &&) oder den Block in eine eigene "
                        + ".js-Datei verschieben. Eine CDATA-Sektion ist innerhalb von h:form keine "
                        + "Loesung (siehe Regel 1).");
    }

    /**
     * Gegenprobe: genau der Fehler, der PROD lahmgelegt hat, muss anschlagen — und die
     * <b>richtige</b> Schreibweise darf es nicht. Der zweite Fall ist der wichtigere: eine Regel,
     * die korrekten Code anmahnt, wird im naechsten Modul abgeschaltet.
     */
    @Test
    void dieWohlgeformtheitsPruefungTrenntRichtigVonFalsch() {
        assertEquals(1, scriptBloeckeOhneCdata("<script>\n var a = 1;\n</script>").size(),
                "Ein Block ohne CDATA muss als solcher erkannt werden.");

        assertTrue(parserFehler("// er enthaelt weder '<' noch '&'\n var a = 1;") != null,
                "Rohzeichen im Kommentar sind genau der PROD-Fehler aus messenger-chat.xhtml.");
        assertTrue(parserFehler("if (a < b) {}") != null,
                "Ein rohes < beendet fuer den Parser das script-Element.");

        assertTrue(parserFehler("if (a &amp;&amp; b) { c(); }") == null,
                "So steht es in root's topbar.xhtml und ist richtig: der Parser loest die Entity zu "
                        + "&& auf. Eine Suche nach dem Zeichen & wuerde das faelschlich melden.");
        assertTrue(parserFehler("var t = 'x';") == null, "Harmloses JavaScript darf nicht anschlagen.");

        String mitCdata = "<script>\n //" + CDATA_START + "\n if (a && b) {}\n //" + CDATA_ENDE + "\n</script>";
        assertTrue(scriptBloeckeOhneCdata(mitCdata).isEmpty(),
                "Ein Block MIT CDATA darf hier nicht anschlagen — dort sind Rohzeichen erlaubt.");
    }

    // ── Regel 3 ──────────────────────────────────────────────

    @Test
    void keineEntityInnerhalbEinerCdataSektion() throws IOException {
        List<String> verstoesse = new ArrayList<>();
        for (Path datei : facelets()) {
            String text = lies(datei);
            for (int[] block : cdataBloecke(text)) {
                String inhalt = text.substring(block[0], block[1]);
                Matcher e = ENTITY.matcher(inhalt);
                while (e.find()) {
                    if (istZeichenkette(inhalt, e.start(), e.end())) {
                        continue;
                    }
                    verstoesse.add(anzeige(datei) + ":" + zeileVon(text, block[0] + e.start())
                            + " enthaelt " + e.group());
                }
            }
        }
        assertTrue(verstoesse.isEmpty(),
                "Diese CDATA-Sektionen enthalten XML-Entities:\n  "
                        + String.join("\n  ", verstoesse)
                        + "\n\nIn einer CDATA-Sektion wird eine Entity NICHT aufgeloest — der Browser "
                        + "bekommt sie woertlich und bricht das ganze Script mit einem SyntaxError ab. "
                        + "Genau daran ist der Kontakt-Deep-Link gescheitert. Abhilfe: das Zeichen roh "
                        + "schreiben (dafuer ist die CDATA-Sektion da) oder den Block in eine eigene "
                        + ".js-Datei verschieben.");
    }

    /**
     * Gegenprobe: Die Suche muss genau den Fehler finden, der den Deep-Link lahmgelegt hat — und
     * eine Entity ausserhalb der CDATA-Sektion (etwa in einem {@code onclick}-Attribut, wo sie
     * richtig und noetig ist) darf nicht anschlagen.
     */
    @Test
    void dieEntitySucheFindetGenauDenDeepLinkFehler() {
        String kaputt = "<script>//" + CDATA_START + "\n if (window.PF &amp;&amp; PF('dlg')) {}\n //"
                + CDATA_ENDE + "\n</script>";
        List<int[]> bloecke = cdataBloecke(kaputt);
        assertEquals(1, bloecke.size(), "Die CDATA-Sektion muss als solche erkannt werden.");
        assertTrue(ENTITY.matcher(kaputt.substring(bloecke.get(0)[0], bloecke.get(0)[1])).find(),
                "Die Entity in der CDATA-Sektion muss anschlagen — sonst sagt die Regel oben nichts aus.");

        String richtig = "<p:commandButton onclick=\"if (a &amp;&amp; b) { return false; }\"/>";
        assertTrue(cdataBloecke(richtig).isEmpty(),
                "Ausserhalb einer CDATA-Sektion ist die Entity richtig und noetig (Attributwerte werden "
                        + "vom Parser aufgeloest) — hier darf die Regel nicht zuschlagen.");

        // Die Ausnahme: eine Escaping-Tabelle meint die Entity als Zeichenkette (so in root's topbar).
        String escapeTabelle = "{ '&': '&amp;', '<': '&lt;' }";
        Matcher t = ENTITY.matcher(escapeTabelle);
        assertTrue(t.find(), "Die Entity muss im Text ueberhaupt vorkommen.");
        assertTrue(istZeichenkette(escapeTabelle, t.start(), t.end()),
                "Eine in Anfuehrungszeichen stehende Entity ist Absicht und darf nicht als Verstoss "
                        + "gemeldet werden — sonst waere die Regel im naechsten Modul nur noch laestig.");
    }

    /**
     * Belegt den Mechanismus am XML-Parser: Ausserhalb einer CDATA-Sektion wird die Entity
     * aufgeloest, innerhalb bleibt sie woertlich stehen — und genau dieses woertliche
     * {@code &amp;amp;&amp;amp;} landet dann im JavaScript des Browsers.
     */
    @Test
    void inCdataBleibtDieEntityWoertlichStehen() throws Exception {
        assertEquals("if (a && b) {}", textInhalt("<s>if (a &amp;&amp; b) {}</s>"),
                "Ausserhalb einer CDATA-Sektion loest der Parser die Entity auf — so ist es in "
                        + "Attributen und im Markup richtig.");

        assertEquals("if (a &amp;&amp; b) {}",
                textInhalt("<s>" + CDATA_START + "if (a &amp;&amp; b) {}" + CDATA_ENDE + "</s>"),
                "In einer CDATA-Sektion bleibt sie woertlich stehen. Der Browser bekommt damit "
                        + "\"&amp;amp;&amp;amp;\" als JavaScript ausgeliefert und bricht das ganze Script ab.");
    }

    // ── Suchen ───────────────────────────────────────────────

    /** Positionen aller {@code ]]>}, die innerhalb eines {@code h:form}-Bereichs liegen. */
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

    /** Inhaltsbereiche aller CDATA-Sektionen (ohne die Marker selbst). */
    private static List<int[]> cdataBloecke(String text) {
        List<int[]> ergebnis = new ArrayList<>();
        int a = text.indexOf(CDATA_START);
        while (a >= 0) {
            int e = text.indexOf(CDATA_ENDE, a + CDATA_START.length());
            if (e < 0) {
                break;
            }
            ergebnis.add(new int[]{a + CDATA_START.length(), e});
            a = text.indexOf(CDATA_START, e);
        }
        return ergebnis;
    }

    /** Inhaltsbereiche aller {@code <script>}-Elemente, die KEINE CDATA-Sektion enthalten. */
    private static List<int[]> scriptBloeckeOhneCdata(String text) {
        List<int[]> ergebnis = new ArrayList<>();
        Matcher m = SCRIPT_START.matcher(text);
        while (m.find()) {
            int a = m.end();
            int e = text.indexOf("</script>", a);
            if (e < 0) {
                continue;
            }
            if (!text.substring(a, e).contains(CDATA_START)) {
                ergebnis.add(new int[]{a, e});
            }
        }
        return ergebnis;
    }

    /**
     * Die Meldung des XML-Parsers zum Inhalt eines script-Blocks, oder {@code null}, wenn er
     * wohlgeformt ist. Genau diese Pruefung macht Facelets beim Laden der Seite.
     */
    private static String parserFehler(String inhalt) {
        try {
            parse("<s>" + inhalt + "</s>");
            return null;
        } catch (SAXException | IOException | ParserConfigurationException e) {
            return e.getMessage();
        }
    }

    /**
     * Ob die Fundstelle unmittelbar von Anfuehrungszeichen eingefasst ist, also als
     * JavaScript-Zeichenkette dasteht ({@code '&amp;amp;'}) statt als Operator im Code.
     */
    private static boolean istZeichenkette(String inhalt, int von, int bis) {
        if (von == 0 || bis >= inhalt.length()) {
            return false;
        }
        char davor = inhalt.charAt(von - 1);
        char danach = inhalt.charAt(bis);
        return (davor == '\'' || davor == '"') && davor == danach;
    }

    // ── Hilfsmittel ──────────────────────────────────────────

    private static String huelle(String inhalt) {
        return "<partial-response><changes><update id=\"fm\">" + CDATA_START + inhalt + CDATA_ENDE
                + "</update></changes></partial-response>";
    }

    private static String lies(Path datei) throws IOException {
        return Files.readString(datei, StandardCharsets.UTF_8);
    }

    /** Pfad relativ zur Repo-Wurzel, damit die Meldung in jedem Consumer gleich aussieht. */
    private static String anzeige(Path datei) {
        Path wurzel = repoWurzel();
        return wurzel != null && datei.startsWith(wurzel)
                ? wurzel.relativize(datei).toString()
                : datei.toString();
    }

    /**
     * Alle Facelets des Reactors. Gesucht wird ab der Repo-Wurzel; wird die nicht gefunden
     * (isolierter Modul-Build), reicht das Arbeitsverzeichnis. Ein Consumer ohne eigene Views
     * findet nichts — dann ist auch nichts zu pruefen.
     *
     * <p>Einmal je Lauf, danach aus {@link #FACELETS}: die drei Regeln brauchen dieselbe Liste,
     * und ein Verzeichnisbaum ist nach einem Vollbuild kein billiger Aufruf mehr.</p>
     */
    private static synchronized List<Path> facelets() throws IOException {
        if (FACELETS == null) {
            Path wurzel = repoWurzel();
            FACELETS = suche(wurzel != null
                    ? wurzel
                    : Path.of(System.getProperty("user.dir")).toAbsolutePath());
        }
        return FACELETS;
    }

    /**
     * Der Verzeichnisbaum ab {@code start}, ohne {@code target}, {@code node_modules} und
     * {@code .git}.
     *
     * <p>Bewusst {@link Files#walkFileTree} und nicht {@link Files#walk}: letzteres steigt in
     * jeden dieser Baeume ab und filtert erst hinterher — nach einem Vollbuild von plaintext-app
     * sind das Zehntausende Eintraege, dreimal. Und es wirft eine {@code UncheckedIOException},
     * sobald ein Verzeichnis nicht lesbar ist; dann faellt in <b>allen</b> Consumern ein Test aus
     * einem Grund durch, der mit der Regel nichts zu tun hat. {@code visitFileFailed} laesst
     * solche Eintraege hier einfach aus.</p>
     */
    private static List<Path> suche(Path start) throws IOException {
        String sep = java.io.File.separator;
        List<Path> treffer = new ArrayList<>();
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                return UNINTERESSANT.contains(name) ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path datei, BasicFileAttributes attrs) {
                String pfad = datei.toString();
                if (pfad.endsWith(".xhtml") && pfad.contains(sep + "src" + sep + "main" + sep)) {
                    treffer.add(datei);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path datei, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        treffer.sort(null);
        return List.copyOf(treffer);
    }

    /** Repo-Wurzel = erstes Verzeichnis nach oben, das einen Maven-Reactor (pom.xml mit modules) hat. */
    private static Path repoWurzel() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    if (Files.readString(pom, StandardCharsets.UTF_8).contains("<modules>")) {
                        return dir;
                    }
                } catch (IOException ignored) {
                    // weiter nach oben
                }
            }
        }
        return null;
    }

    private static int zeileVon(String text, int pos) {
        return (int) text.substring(0, pos).chars().filter(c -> c == '\n').count() + 1;
    }

    private static void parse(String xml) throws SAXException, IOException, ParserConfigurationException {
        bauer().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Textinhalt des Wurzelelements, so wie der XML-Parser ihn sieht. */
    private static String textInhalt(String xml) throws Exception {
        return bauer().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement()
                .getTextContent();
    }

    /**
     * Parser mit stummem Fehlerbehandler. Ohne ihn schreibt der voreingestellte Behandler jeden
     * Fehler zusaetzlich nach {@code System.err} — die Gegenproben oben erzeugen absichtlich
     * kaputtes XML, und ein Testlauf voller roter Parserausgaben ist nicht mehr lesbar.
     */
    private static DocumentBuilder bauer() throws ParserConfigurationException {
        DocumentBuilder b = fabrik().newDocumentBuilder();
        b.setErrorHandler(new org.xml.sax.ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
                // absichtlich still
            }

            @Override
            public void error(SAXParseException e) throws SAXException {
                throw e;
            }

            @Override
            public void fatalError(SAXParseException e) throws SAXException {
                throw e;
            }
        });
        return b;
    }

    private static DocumentBuilderFactory fabrik() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f;
    }
}

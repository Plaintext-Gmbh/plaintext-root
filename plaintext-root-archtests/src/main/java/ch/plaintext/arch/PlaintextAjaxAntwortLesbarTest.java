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
 * Three rules for inline scripts in Facelets — shared, because each of them has already cost a
 * whole day and none of them makes itself noticed from the outside.
 *
 * <p>The test lives in {@code src/main/java} of the module {@code plaintext-root-archtests} and
 * thereby ends up in the published jar. Consumers (app, iot, schuetu, guild) let it run via Surefire
 * {@code <dependenciesToScan>} against their own Facelets — the rule exists once,
 * not five times. Measured on 23.08.2026 all five repositories are clean.</p>
 *
 * <p><b>Relation to the copies.</b> It supersedes {@code ch.plaintext.webapp.AjaxAntwortLesbarTest}
 * in plaintext-root-webapp, which only knew rule 1: within <b>one</b> repository that is built and
 * rolled back in one go, a second copy of the same rule is merely the place where the two drift
 * apart. The version in plaintext-app deliberately stays — there the reasoning from
 * {@code AjaxZielAufloesbarTest} applies: app is built separately and rolled back separately, and a
 * rule that disappears with a root rollback is gone on the very day it is needed.</p>
 *
 * <h2>Rule 1 — no literal CDATA end inside {@code h:form} (cards 430, 502)</h2>
 * <p>Every PrimeFaces response wraps the updated form in
 * {@code <update id="…"><![CDATA[ … ]]></update>}. If the character sequence {@code ]]>} appears
 * anywhere in the form — typically at the end of an inline script — it terminates that outer section
 * prematurely. The browser's XML parser aborts, PrimeFaces discards the <b>complete</b> response and
 * reports it only via the jQuery event {@code pfAjaxError}, which nobody listens to.</p>
 *
 * <h2>Rule 2 — a {@code script} block without CDATA must be well-formed XML (card 502)</h2>
 * <p>Otherwise Facelets no longer parses the file at all and the page returns HTTP 500. This is
 * measured here with the XML parser itself instead of with a character list: {@code &amp;&amp;} is
 * <b>correct</b> in a block without CDATA (the parser resolves it to {@code &&}, which is how it
 * stands in root's topbar.xhtml), whereas a raw {@code &&} or {@code <} is wrong. A search for the
 * character {@code &} would treat both alike and object to the correct spelling.</p>
 *
 * <h2>Rule 3 — no XML entity inside a CDATA section (card 502, third attempt)</h2>
 * <p>The inverse of rule 2: inside a CDATA section the parser does <b>not</b> resolve entities.
 * Whoever writes {@code &amp;amp;&amp;amp;} there instead of {@code &&} delivers the entity to the
 * browser verbatim; the browser then aborts the whole script with a SyntaxError. That is exactly
 * what broke the contact deep link coming from the global search: HTTP 200, no server log, the only
 * visible symptom being that the dialog did not open. Exempt is the entity that stands there
 * <b>as a character string</b> ({@code '&amp;amp;'}) — an escaping table means exactly that and is
 * correct.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextAjaxAntwortLesbarTest {

    private static final String CDATA_START = "<!" + "[CDATA[";
    private static final String CDATA_ENDE = "]" + "]>";

    /** XML entities that have no business inside a CDATA section. */
    private static final Pattern ENTITY = Pattern.compile("&(amp|lt|gt|quot|apos|#\\d+);");

    private static final Pattern FORM_START = Pattern.compile("<h:form\\b");
    private static final Pattern FORM_ENDE = Pattern.compile("</h:form>");
    private static final Pattern SCRIPT_START = Pattern.compile("<script\\b[^>]*>");

    /** Directories the scan does not even descend into. */
    private static final Set<String> UNINTERESSANT = Set.of("target", "node_modules", ".git");

    /** Collected once per run — all three rules work on the same list. */
    private static List<Path> FACELETS;

    // ── Rule 1 ───────────────────────────────────────────────

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
     * Counter-check on the search itself. Without this case the rule above would be green even if
     * {@link #cdataEndenInFormular} finds nothing at all — and in a shared test that would be the
     * most dangerous variant: five repositories would then hold a promise that promises nothing.
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

    /** Demonstrates the mechanism on the XML parser instead of merely asserting it. */
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

    // ── Rule 2 ───────────────────────────────────────────────

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
     * Counter-check: exactly the defect that took PROD down has to trip the rule — and the
     * <b>correct</b> spelling must not. The second case is the more important one: a rule that
     * objects to correct code gets switched off in the next module.
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

    // ── Rule 3 ───────────────────────────────────────────────

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
     * Counter-check: the search has to find exactly the defect that took the deep link down — and an
     * entity outside the CDATA section (in an {@code onclick} attribute, say, where it is correct and
     * necessary) must not trip it.
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

        // The exception: an escaping table means the entity as a character string (as in root's topbar).
        String escapeTabelle = "{ '&': '&amp;', '<': '&lt;' }";
        Matcher t = ENTITY.matcher(escapeTabelle);
        assertTrue(t.find(), "Die Entity muss im Text ueberhaupt vorkommen.");
        assertTrue(istZeichenkette(escapeTabelle, t.start(), t.end()),
                "Eine in Anfuehrungszeichen stehende Entity ist Absicht und darf nicht als Verstoss "
                        + "gemeldet werden — sonst waere die Regel im naechsten Modul nur noch laestig.");
    }

    /**
     * Demonstrates the mechanism on the XML parser: outside a CDATA section the entity is resolved,
     * inside it stays verbatim — and exactly that verbatim {@code &amp;amp;&amp;amp;} then ends up in
     * the browser's JavaScript.
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

    // ── Searches ─────────────────────────────────────────────

    /** Positions of all {@code ]]>} that lie within an {@code h:form} region. */
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

    /** Content regions of all CDATA sections (without the markers themselves). */
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

    /** Content regions of all {@code <script>} elements that contain NO CDATA section. */
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
     * The XML parser's message about the content of a script block, or {@code null} if it is
     * well-formed. Exactly this check is what Facelets performs when loading the page.
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
     * Whether the hit is immediately enclosed in quotes, i.e. stands there as a JavaScript character
     * string ({@code '&amp;amp;'}) rather than as an operator in the code.
     */
    private static boolean istZeichenkette(String inhalt, int von, int bis) {
        if (von == 0 || bis >= inhalt.length()) {
            return false;
        }
        char davor = inhalt.charAt(von - 1);
        char danach = inhalt.charAt(bis);
        return (davor == '\'' || davor == '"') && davor == danach;
    }

    // ── Helpers ──────────────────────────────────────────────

    private static String huelle(String inhalt) {
        return "<partial-response><changes><update id=\"fm\">" + CDATA_START + inhalt + CDATA_ENDE
                + "</update></changes></partial-response>";
    }

    private static String lies(Path datei) throws IOException {
        return Files.readString(datei, StandardCharsets.UTF_8);
    }

    /** Path relative to the repository root, so that the message looks the same in every consumer. */
    private static String anzeige(Path datei) {
        Path wurzel = repoWurzel();
        return wurzel != null && datei.startsWith(wurzel)
                ? wurzel.relativize(datei).toString()
                : datei.toString();
    }

    /**
     * All Facelets of the reactor. The search starts at the repository root; if that is not found
     * (isolated module build), the working directory is enough. A consumer without views of its own
     * finds nothing — then there is nothing to check either.
     *
     * <p>Once per run, afterwards from {@link #FACELETS}: the three rules need the same list, and
     * after a full build walking a directory tree is no longer a cheap call.</p>
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
     * The directory tree below {@code start}, without {@code target}, {@code node_modules} and
     * {@code .git}.
     *
     * <p>Deliberately {@link Files#walkFileTree} and not {@link Files#walk}: the latter descends into
     * every one of those trees and filters only afterwards — after a full build of plaintext-app
     * those are tens of thousands of entries, three times over. And it throws an
     * {@code UncheckedIOException} as soon as a directory is unreadable; a test would then fail in
     * <b>all</b> consumers for a reason that has nothing to do with the rule. {@code visitFileFailed}
     * simply skips such entries here.</p>
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

    /** Repository root = first directory upwards that holds a Maven reactor (pom.xml with modules). */
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
                    // keep going upwards
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

    /** Text content of the root element, exactly as the XML parser sees it. */
    private static String textInhalt(String xml) throws Exception {
        return bauer().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement()
                .getTextContent();
    }

    /**
     * Parser with a silent error handler. Without it the default handler additionally writes every
     * error to {@code System.err} — the counter-checks above deliberately produce broken XML, and a
     * test run full of red parser output is no longer readable.
     */
    private static DocumentBuilder bauer() throws ParserConfigurationException {
        DocumentBuilder b = fabrik().newDocumentBuilder();
        b.setErrorHandler(new org.xml.sax.ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
                // deliberately silent
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

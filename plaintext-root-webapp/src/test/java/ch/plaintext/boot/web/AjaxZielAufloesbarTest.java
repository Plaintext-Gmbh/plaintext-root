/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.web;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 514/519: from within a data table an Ajax target has to be named ABSOLUTELY —
 * checked across ALL modules of this repository.
 *
 * <p><b>The defect.</b> If a button stands inside a {@code p:dataTable}/
 * {@code p:dataList} and its {@code update} names a target relatively, PrimeFaces looks for it in
 * the nearest naming container — the table — and, as a last fallback level, at the view root. A
 * target that lies BELOW the form (say {@code fm:versionenDialog}) cannot be found from either of
 * those two places:</p>
 *
 * <pre>
 * ComponentNotFoundException: Cannot find component for expressions "versionenDialog"
 *   referenced from "fm:j_idt221:0:j_idt229"
 *     at DataTableRenderer.encodeCell(DataTableRenderer.java:1331)
 * </pre>
 *
 * <p><b>Why that was so expensive.</b> The exception is thrown while the cell is being
 * <em>rendered</em>. The partial response breaks off in the middle of the markup — measured on
 * 03.08.2026 in PROD: 1667 bytes, no closing {@code partial-response}. The browser cannot parse it
 * and keeps the old state. The only visible symptom is: <em>nothing happens.</em> On the server side
 * only a WARN line was left, which never reached Graylog. In the wiki Daniel therefore
 * <b>never</b> saw the version history (card 473), and it cost three rounds of diagnosis.
 *
 * <p><b>Why repository-wide.</b> Card 519 counted 34 candidates in this repository, spread
 * across the admin modules and root-webapp. Copying a check into every module is the road on which
 * the next occurrence goes unnoticed (the same reasoning as with
 * {@code AjaxAntwortLesbarTest}, card 502). The word-identical version lives in plaintext-app —
 * deliberately, because both repositories are built separately and rolled back separately.
 *
 * <p><b>What the test does NOT check:</b> whether the target exists. Only that it is
 * <em>formulated</em> in a way that makes it findable from a table row at all — with a leading colon
 * or as an {@code @} keyword. It also does not trip on a form id: an
 * {@code h:form} is a direct child of the view root and can therefore be resolved from the row as
 * well. Exactly for that reason {@code update="fm"} demonstrably works in the page tree of the wiki.
 */
class AjaxZielAufloesbarTest {

    private static final Pattern TABELLE_AUF =
            Pattern.compile("<p:(dataTable|dataList|dataGrid|treeTable)\\b");
    private static final Pattern TABELLE_ZU =
            Pattern.compile("</p:(dataTable|dataList|dataGrid|treeTable)>");
    private static final Pattern ZIEL = Pattern.compile("\\b(update|process)=\"([^\"]+)\"");
    private static final Pattern FORMULAR = Pattern.compile("<h:form\\b[^>]*\\bid=\"([^\"]+)\"");
    private static final Pattern KOMMENTAR = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /**
     * PrimeFaces separates several targets by a blank OR a comma
     * ({@code update="runningTable,:fm:messages"} does occur in the repository).
     */
    private static final Pattern TRENNER = Pattern.compile("[\\s,]+");

    @Test
    void ajaxZieleAusTabellenSindAbsolut() throws IOException {
        List<String> verstoesse = new ArrayList<>();
        List<Path> dateien = facelets();
        for (Path datei : dateien) {
            String text = Files.readString(datei, StandardCharsets.UTF_8);
            for (Fund fund : relativeZieleInTabellen(text)) {
                verstoesse.add(repoWurzel().relativize(datei) + ":" + zeileVon(text, fund.position())
                        + "  ->  " + fund.ziel());
            }
        }
        assertTrue(dateien.size() >= 20,
                "Es wurden nur " + dateien.size() + " Facelets gefunden — vermutlich sucht der Test "
                        + "an der falschen Stelle (" + repoWurzel() + "). Ein gruener Lauf waere wertlos.");
        assertTrue(verstoesse.isEmpty(),
                "Diese Ajax-Ziele stehen in einer Datentabelle und sind relativ benannt:\n  "
                        + String.join("\n  ", verstoesse)
                        + "\n\nAus einer Tabellenzeile heraus sucht PrimeFaces relativ nur in der "
                        + "Tabelle und an der View-Wurzel. Liegt das Ziel unterhalb des Formulars, "
                        + "wirft der Renderer eine ComponentNotFoundException, die Partial-Response "
                        + "bricht mitten im Markup ab und der Browser verwirft sie stillschweigend — "
                        + "sichtbar ist nur, dass der Klick nichts tut. Abhilfe: Ziel absolut "
                        + "schreiben (fuehrender Doppelpunkt, z. B. \":fm:versionenDialog\") oder ein "
                        + "@-Schluesselwort verwenden (Karte 514/519).");
    }

    /**
     * Counter-check on the search itself. Without it the test above would be green even if
     * {@link #relativeZieleInTabellen(String)} finds nothing at all — and the four
     * non-hits prevent the opposite: a test that reports half the application wrongly.
     */
    @Test
    void dieSucheFindetGenauDenPROD_Fehler() {
        String kaputt = "<p:dataTable value=\"#{b.versionen}\" var=\"v\">"
                + "<p:column><p:commandButton update=\"versionenDialog\"/></p:column>"
                + "</p:dataTable>";
        assertEquals(1, relativeZieleInTabellen(kaputt).size(),
                "Der relative Verweis aus der Tabelle muss auffallen.");

        String formularZiel = "<h:form id=\"fm\"><p:dataTable var=\"v\"><p:column>"
                + "<p:commandButton update=\"fm\"/></p:column></p:dataTable></h:form>";
        assertTrue(relativeZieleInTabellen(formularZiel).isEmpty(),
                "Ein Formular ist von der View-Wurzel aus aufloesbar — update=\"fm\" darf nicht "
                        + "anschlagen, sonst meldet der Test die halbe Anwendung falsch.");

        String heil = kaputt.replace("update=\"versionenDialog\"", "update=\":fm:versionenDialog\"");
        assertTrue(relativeZieleInTabellen(heil).isEmpty(),
                "Ein absolutes Ziel darf nicht anschlagen — sonst ist die Regel unbrauchbar.");

        String ausserhalb = "<p:commandButton update=\"versionenDialog\"/>"
                + "<p:dataTable var=\"v\"><p:column>x</p:column></p:dataTable>";
        assertTrue(relativeZieleInTabellen(ausserhalb).isEmpty(),
                "Ausserhalb einer Tabelle ist ein relatives Ziel in Ordnung — hier darf nichts "
                        + "anschlagen.");

        String schluessel = "<p:dataTable var=\"v\"><p:column>"
                + "<p:commandButton update=\"@form\" process=\"@this\"/></p:column></p:dataTable>";
        assertTrue(relativeZieleInTabellen(schluessel).isEmpty(),
                "@-Schluesselwoerter sind aufloesbar und duerfen nicht anschlagen.");
    }

    /**
     * Two cases in which the first version of this search got it wrong — both real in the repository
     * (card 519).
     */
    @Test
    void dieSucheLiestKommentareNichtUndKenntDasKomma() {
        // korrespondenz.xhtml explains in a comment IN THE MIDDLE of a dataTable why a
        // GET link stands there — and quotes update="messages" while doing so. A false alarm claims
        // that action is needed where none is, and thereby costs just as much time as an overlooked
        // defect.
        String mitKommentar = "<p:dataTable var=\"v\"><p:column>"
                + "<!-- frueher stand hier update=\"messages\", siehe Karte 430 -->"
                + "<h:outputLink value=\"detail.xhtml\"/></p:column></p:dataTable>";
        assertTrue(relativeZieleInTabellen(mitKommentar).isEmpty(),
                "Ein zitiertes update= in einem XML-Kommentar ist kein Ajax-Ziel.");

        // PrimeFaces also separates targets with a comma. Without that separator the search sees
        // "runningTable,:fm:messages" as ONE target and reports the whole string — the finding
        // then happens to be right, but the message is useless.
        String komma = "<h:form id=\"fm\"><p:dataTable var=\"v\"><p:column>"
                + "<p:commandButton update=\"runningTable,:fm:messages\"/></p:column>"
                + "</p:dataTable></h:form>";
        List<Fund> funde = relativeZieleInTabellen(komma);
        assertEquals(1, funde.size(), "Genau das relative Ziel muss auffallen, nicht beide.");
        assertEquals("runningTable", funde.get(0).ziel(),
                "Gemeldet werden muss das einzelne Ziel, nicht die ganze Liste.");

        // Third case, real in plaintext-root: a target WITH a form prefix, but without a
        // leading colon. From the view root "fm:messages" can be found, because the
        // form is its direct child — nothing must trip here.
        String mitFormularPraefix = "<h:form id=\"fm\"><p:dataTable var=\"v\"><p:column>"
                + "<p:commandButton update=\"fm:messages\"/></p:column></p:dataTable></h:form>";
        assertTrue(relativeZieleInTabellen(mitFormularPraefix).isEmpty(),
                "update=\"fm:messages\" ist ueber die View-Wurzel aufloesbar und darf nicht "
                        + "anschlagen — sonst meldet der Test drei Stellen im Framework falsch.");
    }

    // ── Helpers ──────────────────────────────────────────────

    /** A relatively named Ajax target inside a data table. */
    private record Fund(int position, String ziel) {
    }

    /**
     * A target is uncritical if it <em>is</em> a form or lies <em>below</em> one:
     * {@code fm} and {@code fm:messages} are both found at the view root, because the
     * {@code h:form} is its direct child. Only a target that begins neither with {@code :} nor with
     * {@code @} nor with a form id can run into the void from the root.
     *
     * <p>Without the prefix case the test reports {@code update="fm:messages"} as a violation — in the
     * framework repository that affected three places ({@code webhooks.xhtml}, {@code useradmin.xhtml})
     * where nothing is broken.
     */
    private static boolean ueberEinFormularAufloesbar(String ziel, Set<String> formulare) {
        for (String formular : formulare) {
            if (ziel.equals(formular) || ziel.startsWith(formular + ":")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ids of the forms of the file. They are direct children of the view root and therefore
     * resolvable relatively from a table row as well (PrimeFaces falls back to the root).
     */
    private static Set<String> formularIds(String text) {
        Set<String> ids = new HashSet<>();
        Matcher m = FORMULAR.matcher(text);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /**
     * Replaces XML comments with blanks — the content is gone, the positions (and thereby the
     * line numbers in the error message) are preserved.
     *
     * <p>Without this the test reports places where there is nothing to do: in
     * {@code korrespondenz.xhtml} a comment in the middle of a {@code p:dataTable} explains why
     * a GET link stands there instead of an Ajax button — and quotes {@code update="messages"} in doing so.
     * A false alarm in a test that claims action is needed costs just as much time as an
     * overlooked defect.
     */
    private static String ohneKommentare(String text) {
        return KOMMENTAR.matcher(text).replaceAll(treffer -> " ".repeat(treffer.group().length()));
    }

    /** All relatively named update/process targets inside a data table. */
    private static List<Fund> relativeZieleInTabellen(String roh) {
        String text = ohneKommentare(roh);
        Set<String> formulare = formularIds(text);
        List<int[]> bereiche = new ArrayList<>();
        Matcher auf = TABELLE_AUF.matcher(text);
        while (auf.find()) {
            Matcher zu = TABELLE_ZU.matcher(text);
            int ende = zu.find(auf.end()) ? zu.start() : text.length();
            bereiche.add(new int[]{auf.start(), ende});
        }
        List<Fund> treffer = new ArrayList<>();
        Matcher m = ZIEL.matcher(text);
        while (m.find()) {
            final int pos = m.start();
            if (bereiche.stream().noneMatch(b -> b[0] < pos && pos < b[1])) {
                continue;
            }
            for (String ziel : TRENNER.split(m.group(2).trim())) {
                if (ziel.isEmpty() || ziel.startsWith("@") || ziel.startsWith(":")
                        || ueberEinFormularAufloesbar(ziel, formulare)) {
                    continue;
                }
                treffer.add(new Fund(pos, ziel));
            }
        }
        return treffer;
    }

    /** All Facelets of the repository (all modules), without build directories. */
    private static List<Path> facelets() throws IOException {
        try (Stream<Path> s = Files.walk(repoWurzel())) {
            return s.filter(p -> p.toString().endsWith(".xhtml"))
                    .filter(p -> p.toString().contains("src" + File.separator + "main"))
                    .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                    .toList();
        }
    }

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
                    // keep going upwards
                }
            }
        }
        throw new IllegalStateException(
                "Sammel-POM nicht gefunden — der Test weiss nicht, wo das Repository beginnt. "
                        + "Startverzeichnis war " + Path.of("").toAbsolutePath());
    }

    private static int zeileVon(String text, int pos) {
        return (int) text.substring(0, pos).chars().filter(c -> c == '\n').count() + 1;
    }
}

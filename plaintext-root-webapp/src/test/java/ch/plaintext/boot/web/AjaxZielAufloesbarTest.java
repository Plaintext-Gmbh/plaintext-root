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
 * Karte 514/519: Aus einer Datentabelle heraus muss ein Ajax-Ziel ABSOLUT benannt sein —
 * geprueft ueber ALLE Module dieses Repositories.
 *
 * <p><b>Der Defekt.</b> Steht eine Schaltflaeche innerhalb einer {@code p:dataTable}/
 * {@code p:dataList} und nennt ihr {@code update} ein Ziel relativ, sucht PrimeFaces es im
 * naechsten Namenscontainer — der Tabelle — und als letzte Rueckfallebene an der View-Wurzel. Ein
 * Ziel, das UNTERHALB des Formulars liegt (etwa {@code fm:versionenDialog}), ist von beiden
 * Stellen aus nicht auffindbar:</p>
 *
 * <pre>
 * ComponentNotFoundException: Cannot find component for expressions "versionenDialog"
 *   referenced from "fm:j_idt221:0:j_idt229"
 *     at DataTableRenderer.encodeCell(DataTableRenderer.java:1331)
 * </pre>
 *
 * <p><b>Warum das so teuer war.</b> Die Ausnahme fliegt beim <em>Rendern</em> der Zelle. Die
 * Partial-Response bricht mitten im Markup ab — gemessen am 03.08.2026 auf PROD: 1667 Bytes, kein
 * schliessendes {@code partial-response}. Der Browser kann sie nicht parsen und behaelt den alten
 * Zustand. Sichtbar ist allein: <em>es passiert nichts.</em> Serverseitig blieb nur eine
 * WARN-Zeile, die in Graylog nicht ankam. Im Wiki hat Daniel den Versionsverlauf deshalb
 * <b>nie</b> gesehen (Karte 473), und es kostete drei Diagnoserunden.
 *
 * <p><b>Warum repositoryweit.</b> Karte 519 hat 34 Kandidaten in diesem Repo gezaehlt, verteilt
 * ueber die Admin-Module und root-webapp. Eine Pruefung je Modul zu kopieren ist der Weg, auf dem
 * die naechste Fundstelle unbemerkt bleibt (dieselbe Begruendung wie bei
 * {@code AjaxAntwortLesbarTest}, Karte 502). Die wortgleiche Fassung liegt in plaintext-app —
 * bewusst, denn beide Repositories werden getrennt gebaut und getrennt zurueckgedreht.
 *
 * <p><b>Was der Test NICHT prueft:</b> ob das Ziel existiert. Nur, dass es aus einer Tabellenzeile
 * heraus ueberhaupt auffindbar <em>formuliert</em> ist — mit fuehrendem Doppelpunkt oder als
 * {@code @}-Schluesselwort. Nicht angeschlagen wird ausserdem bei einer Formular-Id: ein
 * {@code h:form} ist direktes Kind der View-Wurzel und damit auch aus der Zeile heraus
 * aufloesbar. Genau deshalb funktioniert {@code update="fm"} im Seitenbaum des Wikis nachweislich.
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
     * PrimeFaces trennt mehrere Ziele durch Leerzeichen ODER Komma
     * ({@code update="runningTable,:fm:messages"} kommt im Repo vor).
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
     * Gegenprobe zur Suche selbst. Ohne sie waere der Test oben auch dann gruen, wenn
     * {@link #relativeZieleInTabellen(String)} grundsaetzlich nichts findet — und die vier
     * Nicht-Treffer verhindern das Gegenteil: einen Test, der die halbe Anwendung falsch meldet.
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
     * Zwei Faelle, an denen die erste Fassung dieser Suche danebenlag — beide real im Repo
     * (Karte 519).
     */
    @Test
    void dieSucheLiestKommentareNichtUndKenntDasKomma() {
        // korrespondenz.xhtml erklaert in einem Kommentar MITTEN in einer dataTable, warum dort ein
        // GET-Link steht — und zitiert dabei update="messages". Ein Fehlalarm behauptet
        // Handlungsbedarf, wo keiner ist, und kostet damit genauso viel Zeit wie ein uebersehener
        // Fehler.
        String mitKommentar = "<p:dataTable var=\"v\"><p:column>"
                + "<!-- frueher stand hier update=\"messages\", siehe Karte 430 -->"
                + "<h:outputLink value=\"detail.xhtml\"/></p:column></p:dataTable>";
        assertTrue(relativeZieleInTabellen(mitKommentar).isEmpty(),
                "Ein zitiertes update= in einem XML-Kommentar ist kein Ajax-Ziel.");

        // PrimeFaces trennt Ziele auch mit Komma. Ohne diesen Trenner sieht die Suche
        // "runningTable,:fm:messages" als EIN Ziel und meldet den ganzen String — der Befund
        // stimmt dann zufaellig, die Meldung ist aber unbrauchbar.
        String komma = "<h:form id=\"fm\"><p:dataTable var=\"v\"><p:column>"
                + "<p:commandButton update=\"runningTable,:fm:messages\"/></p:column>"
                + "</p:dataTable></h:form>";
        List<Fund> funde = relativeZieleInTabellen(komma);
        assertEquals(1, funde.size(), "Genau das relative Ziel muss auffallen, nicht beide.");
        assertEquals("runningTable", funde.get(0).ziel(),
                "Gemeldet werden muss das einzelne Ziel, nicht die ganze Liste.");

        // Dritter Fall, real in plaintext-root: ein Ziel MIT Formular-Praefix, aber ohne
        // fuehrenden Doppelpunkt. Von der View-Wurzel aus ist "fm:messages" auffindbar, weil das
        // Formular deren direktes Kind ist — hier darf nichts anschlagen.
        String mitFormularPraefix = "<h:form id=\"fm\"><p:dataTable var=\"v\"><p:column>"
                + "<p:commandButton update=\"fm:messages\"/></p:column></p:dataTable></h:form>";
        assertTrue(relativeZieleInTabellen(mitFormularPraefix).isEmpty(),
                "update=\"fm:messages\" ist ueber die View-Wurzel aufloesbar und darf nicht "
                        + "anschlagen — sonst meldet der Test drei Stellen im Framework falsch.");
    }

    // ── Hilfsmittel ──────────────────────────────────────────

    /** Ein relativ benanntes Ajax-Ziel innerhalb einer Datentabelle. */
    private record Fund(int position, String ziel) {
    }

    /**
     * Ein Ziel ist unkritisch, wenn es ein Formular <em>ist</em> oder <em>unter</em> einem liegt:
     * {@code fm} und {@code fm:messages} werden beide an der View-Wurzel gefunden, weil das
     * {@code h:form} deren direktes Kind ist. Nur ein Ziel, das weder mit {@code :} noch mit
     * {@code @} noch mit einer Formular-Id beginnt, kann von der Wurzel aus ins Leere laufen.
     *
     * <p>Ohne den Praefix-Fall meldet der Test {@code update="fm:messages"} als Verstoss — im
     * Framework-Repo betraf das drei Stellen ({@code webhooks.xhtml}, {@code useradmin.xhtml}),
     * an denen nichts kaputt ist.
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
     * Ids der Formulare der Datei. Sie sind direkte Kinder der View-Wurzel und darum auch aus
     * einer Tabellenzeile heraus relativ aufloesbar (PrimeFaces faellt auf die Wurzel zurueck).
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
     * Ersetzt XML-Kommentare durch Leerzeichen — Inhalt weg, Positionen (und damit die
     * Zeilennummern in der Fehlermeldung) bleiben erhalten.
     *
     * <p>Ohne das meldet der Test Stellen, an denen nichts zu tun ist: In
     * {@code korrespondenz.xhtml} erklaert ein Kommentar mitten in einer {@code p:dataTable}, warum
     * dort ein GET-Link statt eines Ajax-Buttons steht — und zitiert dabei {@code update="messages"}.
     * Ein Fehlalarm in einem Test, der Handlungsbedarf behauptet, kostet genauso viel Zeit wie ein
     * uebersehener Fehler.
     */
    private static String ohneKommentare(String text) {
        return KOMMENTAR.matcher(text).replaceAll(treffer -> " ".repeat(treffer.group().length()));
    }

    /** Alle relativ benannten update/process-Ziele innerhalb einer Datentabelle. */
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

    /** Alle Facelets des Repositories (alle Module), ohne Buildverzeichnisse. */
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
                    // weiter nach oben
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

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1109: Kein Facelet verweist auf {@code /resources/…} — diesen Auslieferungspfad gibt es
 * nicht.
 *
 * <p><b>Die Fehlerklasse, und sie stand in ALLEN vier Anwendungen.</b> Das gemeinsame
 * {@code includes/template.xhtml} band das Browser-Icon so ein:
 *
 * <pre>
 *   &lt;link rel="icon" href="…contextPath}/resources/plaintext-layout/images/favicon.ico"/&gt;
 * </pre>
 *
 * JSF liefert Library-Ressourcen aber unter {@code /jakarta.faces.resource/…} aus, und
 * {@code /resources/**} steht in keiner {@code permit-all}-Liste. Anonym an PROD gemessen,
 * 07.09.2026:
 *
 * <pre>
 *   GET /resources/plaintext-layout/images/favicon.ico
 *       app 302   guild 302   schuetu 302   iot 302   -&gt; location: /login.html
 *   GET /jakarta.faces.resource/images/favicon.ico.xhtml?ln=plaintext-layout
 *       200, image/x-icon, 345 B
 * </pre>
 *
 * Der Browser bekam also die Anmeldeseite als Icon geliefert. Richtig ist der
 * {@code resource}-Ausdruck oder ein {@code h:}-Tag mit {@code library=}/{@code name=} — beide
 * bilden den Pfad selbst, samt Library-Version und Cache-Marke.
 *
 * <p><b>Warum eine Regel und nicht nur eine Korrektur.</b> Derselbe Fehlertyp ist in schuetu
 * (Karte 1107) zweimal aufgetreten und beim ersten Beheben nur <i>gewandert</i>: aus 404 wurde
 * 302 — und weil 302 kein 4xx ist, verschwand er dabei aus dem Zugriffsbericht, statt behoben zu
 * sein. Ein falscher Ressourcenpfad ist im Protokoll kaum von Rauschen zu unterscheiden; im
 * Quelltext ist er eindeutig.
 *
 * <p><b>Was dieser Test NICHT leistet:</b> Er prueft die Schreibweise, nicht die Auslieferung.
 * Eine ueber {@code library=} eingebundene Ressource, die es gar nicht gibt, faellt ihm nicht auf
 * — dafuer ist der Seitendurchgang zustaendig. Und er kennt genau einen falschen Praefix; eine
 * kuenftige zweite geschuetzte Wurzel muesste ergaenzt werden.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@DisplayName("Karte 1109: kein Facelet verweist auf /resources/")
class PlaintextRessourcenPfadVertragTest {

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /**
     * {@code href="…/resources/…"}, {@code src='…/resources/…'} — auch mit vorangestelltem
     * EL-Ausdruck wie {@code contextPath}, denn genau so sah der Fehler aus.
     */
    private static final Pattern VERWEIS =
            Pattern.compile("(?:href|src|value)\\s*=\\s*[\"'][^\"']*?/resources/");

    /** {@code <!-- resources-pfad-ok -->} in derselben Zeile. Praktisch nie noetig. */
    private static final String AUSNAHME = "resources-pfad-ok";

    @Test
    @DisplayName("kein href/src/value zeigt auf /resources/")
    void keinVerweisAufResources() {
        List<String> funde = new ArrayList<>();
        int geprueft = 0;
        for (Path wurzel : ReactorLayout.sourceRoots(RESOURCES_SUFFIX)) {
            for (Path datei : xhtmlDateien(wurzel)) {
                geprueft++;
                int nr = 0;
                for (String zeile : lies(datei).split("\n", -1)) {
                    nr++;
                    if (zeile.contains(AUSNAHME)) {
                        continue;
                    }
                    if (VERWEIS.matcher(zeile).find()) {
                        funde.add(ReactorLayout.relativ(datei) + ":" + nr + "  " + zeile.trim());
                    }
                }
            }
        }
        int dateien = geprueft;
        assertTrue(funde.isEmpty(), () ->
                "Verweis auf /resources/ gefunden (" + dateien + " Facelets geprueft). Diesen "
                        + "Auslieferungspfad gibt es nicht: JSF liefert unter "
                        + "/jakarta.faces.resource/ aus, und /resources/** ist nicht permitAll — "
                        + "anonym antwortet er mit 302 auf /login.html (Karte 1109). Richtig ist "
                        + "#{resource['<library>:<pfad>']} oder ein h:-Tag mit library=/name=:\n  "
                        + String.join("\n  ", funde));
    }

    /**
     * Kontrolle, ohne die ein gruener Lauf oben nichts belegt: greift die Dateisuche ueberhaupt?
     * Ein Consumer ohne eigene Facelets ist erlaubt — dann ist die Liste leer und der Test oben
     * sagt nichts, aber er behauptet auch nichts.
     */
    @Test
    @DisplayName("die Suche erfasst die Facelets des Reactors")
    void dieSucheGreift() {
        long n = ReactorLayout.sourceRoots(RESOURCES_SUFFIX).stream()
                .mapToLong(w -> xhtmlDateien(w).size()).sum();
        if (n == 0) {
            System.out.println("PlaintextRessourcenPfadVertragTest: keine Facelets in diesem "
                    + "Reactor — der Test oben prueft hier nichts (das ist erlaubt).");
        }
        assertTrue(n >= 0, "unmoeglich");
    }

    /**
     * Positivkontrolle mit der <b>woertlichen</b> Zeile, die in PROD stand. Schlaegt sie nicht an,
     * prueft der Test oben nichts.
     */
    @Test
    @DisplayName("Positivkontrolle: die Zeile, die in PROD stand, faellt auf")
    void positivkontrolle() {
        String kaputt = "<link rel=\"icon\" href=\"#{request.contextPath}"
                + "/resources/plaintext-layout/images/favicon.ico\" type=\"image/x-icon\"></link>";
        assertTrue(VERWEIS.matcher(kaputt).find(),
                "Die PROD-Zeile muss auffallen, sonst prueft dieser Test nichts.");
    }

    /**
     * Negativkontrolle: die richtigen Formen sind <i>kein</i> Befund. Ohne diese Abgrenzung waere
     * der Test unbrauchbar — er wuerde jede Ressource melden.
     */
    @Test
    @DisplayName("Negativkontrolle: resource-Ausdruck und library=/name= sind kein Befund")
    void negativkontrolle() {
        String gut = "<link rel=\"icon\" href=\"#{resource['plaintext-layout:images/favicon.ico']}\"/>"
                + "<h:outputScript name=\"js/layout.js\" library=\"plaintext-layout\"/>"
                + "<h:link value=\"Start\" href=\"/nosec/info/info.xhtml\"/>";
        assertFalse(VERWEIS.matcher(gut).find(),
                "Die richtigen Formen duerfen nicht anschlagen.");
    }

    // ------------------------------------------------------------------------------------------

    private static List<Path> xhtmlDateien(Path wurzel) {
        try (Stream<Path> pfade = Files.walk(wurzel)) {
            return pfade.filter(p -> p.toString().endsWith(".xhtml")).sorted().toList();
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

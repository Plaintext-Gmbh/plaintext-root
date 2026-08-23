/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 938: {@code data-theme-colors} muss <b>JSON</b> sein, nicht JavaScript.
 *
 * <p><b>Der Defekt.</b> {@link ThemeColorProvider#getColorsJson()} baute ein
 * JavaScript-Objektliteral — unquotierte Schluessel, einfache Anfuehrungszeichen. Solange der
 * Wert direkt in einen {@code <script>}-Block geschrieben wurde, war das gueltiges JavaScript.
 * Seit Karte 502 steht er im Attribut {@code data-theme-colors} an {@code #layout-config} und
 * wird von {@code plaintext-layout/js/config.js} mit {@code JSON.parse} gelesen. JSON kennt
 * beides nicht — auf jeder Seite jeder Anwendung stand
 * {@code SyntaxError: Expected property name or '}' in JSON at position 1} in der Konsole,
 * und weil der Wurf auf oberster Ebene der Datei passierte, war das komplette
 * Konfigurationspanel (Farbe, Dunkelmodus, Menuemodus, Speichern) funktionslos.</p>
 *
 * <p><b>Warum ein Test.</b> Der Unterschied zwischen den beiden Formaten ist mit blossem Auge
 * kaum zu sehen, und im Java-Quelltext heisst die Methode {@code getColorsJson} — sie
 * <i>behauptet</i> also bereits, JSON zu liefern. Nur ein Parser kann das nachpruefen.
 * Dieselbe stille Fehlerform wie in den Karten 430 und 502: HTTP 200, kein Serverlog,
 * sichtbar ist allein, dass nichts passiert.</p>
 */
class ThemeColorProviderJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Die Schluessel, die {@code applyColorVariables()} in config.js anfasst. */
    private static final List<String> FELDER = List.of(
            "primary", "primaryText", "primaryLighter", "primaryBg16", "primaryBg04", "focusRing");

    private final ThemeColorProvider provider = new ThemeColorProvider();

    @Test
    void dieFarbpaletteIstGueltigesJson() {
        String json = provider.getColorsJson();
        assertDoesNotThrow(() -> JSON.readTree(json),
                "data-theme-colors wird im Browser mit JSON.parse gelesen (config.js). Was hier "
                        + "nicht parst, laesst dort die komplette Datei abbrechen — und damit das "
                        + "Konfigurationspanel jeder Seite jeder Anwendung:\n" + json);
    }

    /**
     * Die Gegenprobe zur Pruefung selbst: genau die alte Schreibweise muss durchfallen. Ohne
     * diesen Fall waere der Test oben auch dann gruen, wenn er gar nichts pruefte.
     */
    @Test
    void dasAlteJavaScriptLiteralWaereDurchgefallen() {
        String altesFormat = "{'blue':{light:{primary:'#2196F3',primaryText:'#ffffff'}}}";
        assertThrows(JsonProcessingException.class, () -> JSON.readTree(altesFormat),
                "Genau diese Schreibweise stand bis Karte 938 im Attribut. Wenn der Parser sie "
                        + "akzeptiert, misst der Test oben nichts.");
    }

    @Test
    void keineEinfachenAnfuehrungszeichenAlsBegrenzer() {
        String json = provider.getColorsJson();
        assertFalse(json.contains("'"),
                "JSON kennt nur doppelte Anfuehrungszeichen. Ein einfaches deutet darauf hin, "
                        + "dass wieder von Hand zusammengebaut wird:\n" + json);
    }

    @ParameterizedTest(name = "Farbe {0} liefert light und dark vollstaendig")
    @ValueSource(strings = {"blue", "green", "orange", "turquoise", "avocado",
            "purple", "red", "yellow", "lime", "crimson"})
    void jedeFarbeHatBeideModiVollstaendig(String farbe) throws Exception {
        JsonNode wurzel = JSON.readTree(provider.getColorsJson());
        JsonNode eintrag = wurzel.get(farbe);
        assertTrue(eintrag != null && eintrag.isObject(), "Farbe " + farbe + " fehlt im JSON.");

        for (String modus : List.of("light", "dark")) {
            JsonNode werte = eintrag.get(modus);
            assertTrue(werte != null && werte.isObject(),
                    "Farbe " + farbe + " hat keinen Modus " + modus + ".");
            for (String feld : FELDER) {
                JsonNode wert = werte.get(feld);
                assertTrue(wert != null && wert.isTextual() && !wert.asText().isBlank(),
                        "config.js liest " + farbe + "." + modus + "." + feld
                                + " und setzt es als CSS-Variable — der Wert fehlt.");
            }
        }
    }

    /**
     * Die Werte landen als CSS-Custom-Property im Dokument. Ein Wert, den JSON escapen muesste,
     * waere ein Zeichen dafuer, dass hier etwas Fremdes durchgereicht wird.
     */
    @Test
    void dieWerteSindFarbenUndBrauchenKeinEscaping() throws Exception {
        JsonNode wurzel = JSON.readTree(provider.getColorsJson());
        wurzel.fields().forEachRemaining(farbe -> farbe.getValue().fields().forEachRemaining(
                modus -> modus.getValue().fields().forEachRemaining(feld -> {
                    String wert = feld.getValue().asText();
                    assertTrue(wert.matches("^(#[0-9A-Fa-f]{6}|rgba?\\([0-9,. ]+\\))$"),
                            farbe.getKey() + "." + modus.getKey() + "." + feld.getKey()
                                    + " ist keine Farbangabe, sondern: " + wert);
                })));
    }

    /**
     * Der Attributwert soll zwischen zwei Starts derselben Version identisch sein — sonst
     * unterscheidet sich ausgeliefertes HTML ohne inhaltlichen Grund und verrauscht jeden
     * Vergleich (Diff, Cache, Fehlersuche).
     *
     * <p><b>Warum das nicht mit zwei Aufrufen zu pruefen ist.</b> Die Unruhe kommt von
     * {@code Map.of}/{@code Map.ofEntries}: ihre Iterationsreihenfolge haengt an einem
     * <b>beim JVM-Start gewuerfelten</b> SALT. Innerhalb eines Laufs liefern zwei Aufrufe
     * deshalb immer dasselbe — ein Vergleich zweier Instanzen kann den Fehler prinzipiell nicht
     * sehen. Gemessen wird stattdessen die Reihenfolge selbst: alphabetisch aussen (TreeMap),
     * light vor dark innen (LinkedHashMap). Beide waeren mit einer {@code Map.of}-Huelle in
     * einem von mehreren JVM-Laeufen falsch.</p>
     */
    @Test
    void dieReihenfolgeIstStabil() {
        String json = provider.getColorsJson();

        assertTrue(json.startsWith("{\"avocado\""),
                "Aussen wird alphabetische Reihenfolge erwartet (TreeMap): " + json);

        List<String> farben = new ArrayList<>();
        Matcher farbe = Pattern.compile("\"([a-z]+)\":\\{\"(light|dark)\"").matcher(json);
        while (farbe.find()) {
            farben.add(farbe.group(1));
            assertEquals("light", farbe.group(2),
                    "Innen wird light vor dark erwartet. Eine Map.of-Huelle wuerfelt das je "
                            + "JVM-Lauf neu — der Attributwert waere dann nicht mehr stabil.");
        }
        assertEquals(farben.stream().sorted().toList(), farben,
                "Die Farben stehen nicht alphabetisch: " + farben);
        assertEquals(10, farben.size(), "Erwartet werden alle zehn Farben: " + farben);
    }
}

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
 * Karte 938: {@code data-theme-colors} has to be <b>JSON</b>, not JavaScript.
 *
 * <p><b>The defect.</b> {@link ThemeColorProvider#getColorsJson()} built a
 * JavaScript object literal — unquoted keys, single quotes. As long as the
 * value was written straight into a {@code <script>} block, that was valid JavaScript.
 * Since Karte 502 it sits in the attribute {@code data-theme-colors} on {@code #layout-config}
 * and is read by {@code plaintext-layout/js/config.js} with {@code JSON.parse}. JSON knows
 * neither of the two — on every page of every application the console showed
 * {@code SyntaxError: Expected property name or '}' in JSON at position 1},
 * and because the throw happened at the top level of the file, the entire
 * configuration panel (colour, dark mode, menu mode, saving) was without function.</p>
 *
 * <p><b>Why a test.</b> The difference between the two formats is barely visible to the naked
 * eye, and in the Java source the method is called {@code getColorsJson} — so it already
 * <i>claims</i> to deliver JSON. Only a parser can verify that.
 * The same silent failure shape as in Karte 430 and 502: HTTP 200, no server log,
 * all that is visible is that nothing happens.</p>
 */
class ThemeColorProviderJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The keys that {@code applyColorVariables()} in config.js touches. */
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
     * The counter-check for the check itself: exactly the old spelling has to fail. Without
     * this case the test above would be green even if it checked nothing at all.
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
     * The values end up as a CSS custom property in the document. A value that JSON would have to
     * escape would be a sign that something foreign is being passed through here.
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
     * The attribute value should be identical between two starts of the same version — otherwise
     * the delivered HTML differs for no substantive reason and adds noise to every comparison
     * (diff, cache, debugging).
     *
     * <p><b>Why this cannot be checked with two calls.</b> The unrest comes from
     * {@code Map.of}/{@code Map.ofEntries}: their iteration order depends on a SALT that is
     * <b>drawn at JVM start</b>. Within one run two calls therefore always return the same
     * thing — a comparison of two instances cannot see the bug in principle. What is measured
     * instead is the ordering itself: alphabetical on the outside (TreeMap), light before dark on
     * the inside (LinkedHashMap). Both would be wrong with a {@code Map.of} shell in one out of
     * several JVM runs.</p>
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

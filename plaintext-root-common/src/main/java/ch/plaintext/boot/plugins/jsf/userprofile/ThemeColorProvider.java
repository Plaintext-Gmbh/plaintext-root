/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Provides color palette data for the predefined theme colors in both light and dark mode.
 * Used by template.xhtml to set CSS custom properties.
 */
@Component("themeColorProvider")
@Scope(value = "application", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class ThemeColorProvider implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(ThemeColorProvider.class);

    /** Nur zum Schreiben reiner String-Maps verwendet — zustandslos und damit teilbar. */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Getter
    public static class ColorPalette implements Serializable {
        private final String primary;
        private final String primaryText;
        private final String primaryLighter;
        private final String primaryBg16;
        private final String primaryBg04;
        private final String focusRing;
        // Dark mode variants
        private final String primaryDark;
        private final String primaryLighterDark;
        private final String primaryBg16Dark;
        private final String primaryBg04Dark;
        private final String focusRingDark;

        public ColorPalette(String primary, String primaryText,
                            String primaryLighter, String primaryBg16, String primaryBg04, String focusRing,
                            String primaryDark, String primaryLighterDark,
                            String primaryBg16Dark, String primaryBg04Dark, String focusRingDark) {
            this.primary = primary;
            this.primaryText = primaryText;
            this.primaryLighter = primaryLighter;
            this.primaryBg16 = primaryBg16;
            this.primaryBg04 = primaryBg04;
            this.focusRing = focusRing;
            this.primaryDark = primaryDark;
            this.primaryLighterDark = primaryLighterDark;
            this.primaryBg16Dark = primaryBg16Dark;
            this.primaryBg04Dark = primaryBg04Dark;
            this.focusRingDark = focusRingDark;
        }
    }

    private static final Map<String, ColorPalette> PALETTES = Map.ofEntries(
            Map.entry("blue", new ColorPalette(
                    "#2196F3", "#ffffff", "#e3f2fd",
                    "rgba(33,150,243,.16)", "rgba(33,150,243,.04)", "rgba(33,150,243,.5)",
                    "#69B7FF", "#1e3a5f",
                    "rgba(105,183,255,.16)", "rgba(105,183,255,.04)", "rgba(105,183,255,.5)")),
            Map.entry("green", new ColorPalette(
                    "#4CAF50", "#ffffff", "#e8f5e9",
                    "rgba(76,175,80,.16)", "rgba(76,175,80,.04)", "rgba(76,175,80,.5)",
                    "#81C784", "#1b3d1c",
                    "rgba(129,199,132,.16)", "rgba(129,199,132,.04)", "rgba(129,199,132,.5)")),
            Map.entry("orange", new ColorPalette(
                    "#FF9800", "#ffffff", "#fff3e0",
                    "rgba(255,152,0,.16)", "rgba(255,152,0,.04)", "rgba(255,152,0,.5)",
                    "#FFB74D", "#3e2700",
                    "rgba(255,183,77,.16)", "rgba(255,183,77,.04)", "rgba(255,183,77,.5)")),
            Map.entry("turquoise", new ColorPalette(
                    "#00BCD4", "#ffffff", "#e0f7fa",
                    "rgba(0,188,212,.16)", "rgba(0,188,212,.04)", "rgba(0,188,212,.5)",
                    "#4DD0E1", "#003d44",
                    "rgba(77,208,225,.16)", "rgba(77,208,225,.04)", "rgba(77,208,225,.5)")),
            Map.entry("avocado", new ColorPalette(
                    "#AEC523", "#ffffff", "#f4f7e0",
                    "rgba(174,197,35,.16)", "rgba(174,197,35,.04)", "rgba(174,197,35,.5)",
                    "#C6D63E", "#2d3308",
                    "rgba(198,214,62,.16)", "rgba(198,214,62,.04)", "rgba(198,214,62,.5)")),
            Map.entry("purple", new ColorPalette(
                    "#7B1FA2", "#ffffff", "#f3e5f5",
                    "rgba(123,31,162,.16)", "rgba(123,31,162,.04)", "rgba(123,31,162,.5)",
                    "#BA68C8", "#2a0936",
                    "rgba(186,104,200,.16)", "rgba(186,104,200,.04)", "rgba(186,104,200,.5)")),
            Map.entry("red", new ColorPalette(
                    "#F44336", "#ffffff", "#ffebee",
                    "rgba(244,67,54,.16)", "rgba(244,67,54,.04)", "rgba(244,67,54,.5)",
                    "#E57373", "#3e0d0a",
                    "rgba(229,115,115,.16)", "rgba(229,115,115,.04)", "rgba(229,115,115,.5)")),
            Map.entry("yellow", new ColorPalette(
                    "#FFC107", "#212529", "#fff8e1",
                    "rgba(255,193,7,.16)", "rgba(255,193,7,.04)", "rgba(255,193,7,.5)",
                    "#FFD54F", "#3e3000",
                    "rgba(255,213,79,.16)", "rgba(255,213,79,.04)", "rgba(255,213,79,.5)")),
            Map.entry("lime", new ColorPalette(
                    "#8BC34A", "#ffffff", "#f1f8e9",
                    "rgba(139,195,74,.16)", "rgba(139,195,74,.04)", "rgba(139,195,74,.5)",
                    "#AED581", "#263218",
                    "rgba(174,213,129,.16)", "rgba(174,213,129,.04)", "rgba(174,213,129,.5)")),
            Map.entry("crimson", new ColorPalette(
                    "#B71C1C", "#ffffff", "#fce4ec",
                    "rgba(183,28,28,.16)", "rgba(183,28,28,.04)", "rgba(183,28,28,.5)",
                    "#D32F2F", "#3b0a0a",
                    "rgba(211,47,47,.16)", "rgba(211,47,47,.04)", "rgba(211,47,47,.5)"))
    );

    public ColorPalette getPalette(String colorName) {
        return PALETTES.getOrDefault(colorName, PALETTES.get("blue"));
    }

    /**
     * Generates a full ColorPalette from an arbitrary hex color string.
     * Used for custom user-chosen colors.
     *
     * @param hex color in "#RRGGBB" format
     * @return a ColorPalette with light and dark mode variants
     */
    public ColorPalette generatePaletteFromHex(String hex) {
        if (hex == null || !hex.matches("^#[0-9A-Fa-f]{6}$")) {
            return PALETTES.get("blue");
        }

        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);

        // Determine text color based on relative luminance (WCAG formula)
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        String primaryText = luminance > 0.5 ? "#212529" : "#ffffff";

        // Light mode: lighten by 15% for primaryLighter (mix with white)
        String primaryLighter = mixWithWhite(r, g, b, 0.85);

        // Dark mode: lighten by 10% (mix with white)
        int rDark = Math.min(255, r + (int) ((255 - r) * 0.25));
        int gDark = Math.min(255, g + (int) ((255 - g) * 0.25));
        int bDark = Math.min(255, b + (int) ((255 - b) * 0.25));
        String primaryDark = String.format("#%02X%02X%02X", rDark, gDark, bDark);

        // Dark mode lighter: darken (mix with dark background)
        String primaryLighterDark = String.format("#%02x%02x%02x",
                (int) (r * 0.2), (int) (g * 0.2), (int) (b * 0.2));

        return new ColorPalette(
                hex,
                primaryText,
                primaryLighter,
                String.format("rgba(%d,%d,%d,.16)", r, g, b),
                String.format("rgba(%d,%d,%d,.04)", r, g, b),
                String.format("rgba(%d,%d,%d,.5)", r, g, b),
                primaryDark,
                primaryLighterDark,
                String.format("rgba(%d,%d,%d,.16)", rDark, gDark, bDark),
                String.format("rgba(%d,%d,%d,.04)", rDark, gDark, bDark),
                String.format("rgba(%d,%d,%d,.5)", rDark, gDark, bDark)
        );
    }

    private String mixWithWhite(int r, int g, int b, double factor) {
        int rr = (int) (r * factor + 255 * (1 - factor));
        int gg = (int) (g * factor + 255 * (1 - factor));
        int bb = (int) (b * factor + 255 * (1 - factor));
        return String.format("#%02x%02x%02x", Math.min(255, rr), Math.min(255, gg), Math.min(255, bb));
    }

    /**
     * Get color palette for a custom hex color for the given mode.
     * Returns the appropriate variant (light or dark) of the generated palette.
     */
    public ColorPalette getCustomColorPalette(String hex) {
        return generatePaletteFromHex(hex);
    }

    /**
     * Resolves the palette for a color name, supporting "custom" with a hex fallback.
     */
    private ColorPalette resolvePalette(String colorName, String customHex) {
        if ("custom".equals(colorName) && customHex != null && !customHex.isEmpty()) {
            return generatePaletteFromHex(customHex);
        }
        return getPalette(colorName);
    }

    /**
     * Get the primary color for a given theme and mode.
     */
    public String getPrimaryColor(String colorName, String darkMode) {
        ColorPalette p = getPalette(colorName);
        return "dark".equals(darkMode) ? p.getPrimaryDark() : p.getPrimary();
    }

    public String getPrimaryColor(String colorName, String darkMode, String customHex) {
        ColorPalette p = resolvePalette(colorName, customHex);
        return "dark".equals(darkMode) ? p.getPrimaryDark() : p.getPrimary();
    }

    public String getPrimaryColorText(String colorName) {
        return getPalette(colorName).getPrimaryText();
    }

    public String getPrimaryColorText(String colorName, String customHex) {
        return resolvePalette(colorName, customHex).getPrimaryText();
    }

    public String getPrimaryLighter(String colorName, String darkMode) {
        ColorPalette p = getPalette(colorName);
        return "dark".equals(darkMode) ? p.getPrimaryLighterDark() : p.getPrimaryLighter();
    }

    public String getPrimaryLighter(String colorName, String darkMode, String customHex) {
        ColorPalette p = resolvePalette(colorName, customHex);
        return "dark".equals(darkMode) ? p.getPrimaryLighterDark() : p.getPrimaryLighter();
    }

    public String getPrimaryBg16(String colorName, String darkMode) {
        ColorPalette p = getPalette(colorName);
        return "dark".equals(darkMode) ? p.getPrimaryBg16Dark() : p.getPrimaryBg16();
    }

    public String getPrimaryBg16(String colorName, String darkMode, String customHex) {
        ColorPalette p = resolvePalette(colorName, customHex);
        return "dark".equals(darkMode) ? p.getPrimaryBg16Dark() : p.getPrimaryBg16();
    }

    public String getPrimaryBg04(String colorName, String darkMode) {
        ColorPalette p = getPalette(colorName);
        return "dark".equals(darkMode) ? p.getPrimaryBg04Dark() : p.getPrimaryBg04();
    }

    public String getPrimaryBg04(String colorName, String darkMode, String customHex) {
        ColorPalette p = resolvePalette(colorName, customHex);
        return "dark".equals(darkMode) ? p.getPrimaryBg04Dark() : p.getPrimaryBg04();
    }

    public String getFocusRing(String colorName, String darkMode) {
        ColorPalette p = getPalette(colorName);
        return "dark".equals(darkMode) ? p.getFocusRingDark() : p.getFocusRing();
    }

    public String getFocusRing(String colorName, String darkMode, String customHex) {
        ColorPalette p = resolvePalette(colorName, customHex);
        return "dark".equals(darkMode) ? p.getFocusRingDark() : p.getFocusRing();
    }

    /**
     * Die Farbpalette als <b>JSON</b> fuer die Browserseite — Ziel ist das Attribut
     * {@code data-theme-colors} an {@code #layout-config} (includes/config.xhtml), aus dem
     * {@code plaintext-layout/js/config.js} sie mit {@code JSON.parse} liest.
     *
     * <p><b>Karte 938.</b> Bis zum 23.08.2026 baute diese Methode ein
     * <i>JavaScript-Objektliteral</i> statt JSON: Schluessel unquotiert ({@code light:{ … }}),
     * Werte in einfachen Anfuehrungszeichen ({@code primary:'#2196F3'}). Solange der Wert direkt
     * in einen {@code <script>}-Block geschrieben wurde, war das gueltiges JavaScript. Seit
     * Karte 502 steht er in einem data-Attribut und wird mit {@code JSON.parse} gelesen — und
     * JSON kennt weder unquotierte Schluessel noch einfache Anfuehrungszeichen. Auf
     * <b>jeder</b> Seite <b>jeder</b> Anwendung stand seither in der Browserkonsole:</p>
     *
     * <pre>SyntaxError: Expected property name or '}' in JSON at position 1</pre>
     *
     * <p><b>Was das kostete</b> (gemessen am 23.08.2026 gegen PROD 2.1646.0, Skript
     * {@code pw-test/test-themecolors-prod.js}): Der Wurf beendet den Lauf der Datei auf oberster
     * Ebene. Die Funktionsdeklarationen darunter ueberleben zwar — sie werden beim Parsen
     * hochgezogen —, aber jede Anweisung ab Zeile 39 wurde nie ausgefuehrt:</p>
     * <ul>
     *   <li>{@code themeColors} blieb {@code undefined}; ein Klick auf eine Farbe endete in
     *       {@code Cannot read properties of undefined (reading 'green')}.</li>
     *   <li>Der Initialisierungsblock lief nicht, {@code isInitializing} blieb dauerhaft
     *       {@code true} — und {@code handleChange()} steigt in dem Fall sofort aus. Damit taten
     *       auch die Radiogruppen fuer Menuetyp, Hell/Dunkel und Eingabestil <b>nichts</b>.</li>
     *   <li>Die Zuhoerer fuer {@code beforeunload} und den Klick ausserhalb des Panels wurden nie
     *       registriert; nichts wurde gespeichert.</li>
     * </ul>
     *
     * <p>Dieselbe Fehlerform wie in den Karten 430/502: HTTP 200, kein Serverlog, kein
     * {@code console.error} — die Meldung ist ein <i>uncaught error</i> und taucht nur im
     * {@code pageerror}-Ereignis auf. Genau deshalb hat sie auch der Smoke-Test nicht gesehen,
     * der ausschliesslich Konsolenmeldungen vom Typ {@code error} sammelt.</p>
     *
     * <p>Erzeugt wird das JSON deshalb nicht mehr von Hand, sondern von Jackson: Anfuehrungs-
     * zeichen und Escaping sind damit keine Frage der Sorgfalt mehr.</p>
     *
     * <p><b>Reihenfolge.</b> Der Attributwert soll zwischen zwei Starts derselben Version
     * identisch sein — sonst unterscheidet sich ausgeliefertes HTML ohne inhaltlichen Grund, was
     * jeden Vergleich (Diff, Cache, Fehlersuche) verrauscht. Beide Ebenen brauchen dafuer eine
     * Zusicherung: {@link Map#ofEntries} <b>und</b> {@link Map#of} iterieren je JVM-Lauf anders
     * (ihre Streuung haengt an einem beim Start gewuerfelten SALT). Aussen sorgt eine
     * {@link TreeMap} fuer alphabetische Ordnung, innen eine {@link LinkedHashMap} fuer
     * light-vor-dark.</p>
     */
    public String getColorsJson() {
        Map<String, Object> alle = new TreeMap<>();
        PALETTES.forEach((name, p) -> {
            Map<String, Object> modi = new LinkedHashMap<>();
            modi.put("light", modus(p.getPrimary(), p.getPrimaryText(), p.getPrimaryLighter(),
                    p.getPrimaryBg16(), p.getPrimaryBg04(), p.getFocusRing()));
            modi.put("dark", modus(p.getPrimaryDark(), p.getPrimaryText(), p.getPrimaryLighterDark(),
                    p.getPrimaryBg16Dark(), p.getPrimaryBg04Dark(), p.getFocusRingDark()));
            alle.put(name, modi);
        });
        try {
            return JSON.writeValueAsString(alle);
        } catch (JsonProcessingException e) {
            // Kann mit reinen String-Maps nicht eintreten; ein leeres Objekt ist immer noch
            // gueltiges JSON und laesst config.js weiterlaufen (statt die Datei abbrechen zu lassen).
            LOG.error("Farbpalette liess sich nicht als JSON schreiben", e);
            return "{}";
        }
    }

    private static Map<String, String> modus(String primary, String primaryText, String primaryLighter,
                                             String primaryBg16, String primaryBg04, String focusRing) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("primary", primary);
        m.put("primaryText", primaryText);
        m.put("primaryLighter", primaryLighter);
        m.put("primaryBg16", primaryBg16);
        m.put("primaryBg04", primaryBg04);
        m.put("focusRing", focusRing);
        return m;
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Only used to write plain string maps — stateless and therefore shareable. */
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

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

    /**
     * Karte 1548 (UI 2, Entscheid Daniel 15.10.2026): drei Farbvarianten statt zehn.
     *
     * <p><b>Warum genau diese drei.</b> {@code green} ist die Vorgabe jedes neuen Benutzers
     * ({@link UserPreference}), {@code blue} der Rueckfall dieser Klasse fuer jeden unbekannten
     * Namen und fuer eine ungueltige eigene Farbe — beide sind also heute schon die Farben, die
     * am meisten Benutzer sehen, ohne je gewaehlt zu haben. {@code orange} deckt als dritte die
     * warme Haelfte des Farbkreises ab: ohne sie haette ein Benutzer mit Rot, Karmin oder Gelb
     * keine nahe Variante, sondern nur ein kaltes Blau oder Gruen.</p>
     *
     * <p>Die Reihenfolge ist die Reihenfolge im Farbwaehler.</p>
     */
    public static final List<String> VARIANTEN = List.of("green", "blue", "orange");

    /**
     * Rueckfall fuer die sieben gestrichenen Varianten auf die naechstliegende verbliebene — nach
     * Farbton, nicht nach Name: Tuerkis (187 Grad) liegt naeher bei Blau (207) als bei Gruen (122),
     * Violett faellt in den kalten Bereich zu Blau, Avocado und Limette sind Gelbgruen und bleiben
     * gruen, Rot, Karmin und Gelb gehen zu Orange. Die Paletten der gestrichenen Varianten sind
     * entfernt; wer einen alten Namen gespeichert hat (Datenbank oder Cookie
     * {@code plaintext-color}), sieht ab dem ersten Aufruf die Ersatzfarbe, und
     * {@link UserPreferencesBackingBean} schreibt sie beim Laden zurueck.
     */
    static final Map<String, String> RUECKFALL = Map.of(
            "turquoise", "blue",
            "purple", "blue",
            "avocado", "green",
            "lime", "green",
            "red", "orange",
            "crimson", "orange",
            "yellow", "orange");

    /**
     * Die Variante, die fuer {@code name} tatsaechlich gilt: eine gestrichene wird auf ihre
     * naechstliegende abgebildet, alles andere (die drei Varianten, {@code custom}, ein
     * unbekannter Name, {@code null}) bleibt unveraendert — fuer Unbekanntes gilt weiter der
     * Rueckfall von {@link #getPalette(String)}.
     */
    public static String variante(String name) {
        return name == null ? null : RUECKFALL.getOrDefault(name, name);
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
                    "rgba(255,183,77,.16)", "rgba(255,183,77,.04)", "rgba(255,183,77,.5)"))
    );

    public ColorPalette getPalette(String colorName) {
        return PALETTES.getOrDefault(variante(colorName), PALETTES.get("blue"));
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
     * The colour palette as <b>JSON</b> for the browser side — its destination is the attribute
     * {@code data-theme-colors} on {@code #layout-config} (includes/config.xhtml), from which
     * {@code plaintext-layout/js/config.js} reads it with {@code JSON.parse}.
     *
     * <p><b>Karte 938.</b> Until 23.08.2026 this method built a <i>JavaScript object literal</i>
     * instead of JSON: unquoted keys ({@code light:{ … }}), values in single quotes
     * ({@code primary:'#2196F3'}). As long as the value was written straight into a
     * {@code <script>} block, that was valid JavaScript. Since Karte 502 it sits in a data
     * attribute and is read with {@code JSON.parse} — and JSON knows neither unquoted keys nor
     * single quotes. On <b>every</b> page of <b>every</b> application the browser console has
     * shown ever since:</p>
     *
     * <pre>SyntaxError: Expected property name or '}' in JSON at position 1</pre>
     *
     * <p><b>What that cost</b> (measured on 23.08.2026 against PROD 2.1646.0, script
     * {@code pw-test/test-themecolors-prod.js}): the throw ends the run of the file at the top
     * level. The function declarations below it do survive — they are hoisted while parsing — but
     * every statement from line 39 on was never executed:</p>
     * <ul>
     *   <li>{@code themeColors} stayed {@code undefined}; a click on a colour ended in
     *       {@code Cannot read properties of undefined (reading 'green')}.</li>
     *   <li>The initialization block did not run, {@code isInitializing} stayed permanently
     *       {@code true} — and in that case {@code handleChange()} bails out immediately. So the
     *       radio groups for menu type, light/dark and input style did <b>nothing</b> either.</li>
     *   <li>The listeners for {@code beforeunload} and for the click outside the panel were never
     *       registered; nothing was saved.</li>
     * </ul>
     *
     * <p>The same shape of failure as in Karte 430/502: HTTP 200, no server log, no
     * {@code console.error} — the message is an <i>uncaught error</i> and only surfaces in the
     * {@code pageerror} event. That is exactly why the smoke test did not see it either: it
     * collects console messages of type {@code error} only.</p>
     *
     * <p>The JSON is therefore no longer built by hand but by Jackson: quoting and escaping are
     * no longer a matter of diligence.</p>
     *
     * <p><b>Ordering.</b> The attribute value should be identical between two starts of the same
     * version — otherwise the delivered HTML differs for no substantive reason, which adds noise
     * to every comparison (diff, cache, debugging). Both levels need a guarantee for that:
     * {@link Map#ofEntries} <b>and</b> {@link Map#of} iterate differently in every JVM run (their
     * scattering depends on a SALT drawn at startup). On the outside a {@link TreeMap} provides
     * alphabetical order, on the inside a {@link LinkedHashMap} provides light-before-dark.</p>
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
        } catch (JacksonException e) {
            // Cannot happen with plain string maps; an empty object is still
            // valid JSON and lets config.js keep running (instead of aborting the file).
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

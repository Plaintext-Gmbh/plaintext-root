/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Übertragungsobjekt (DTO) für eine Dashboard-Kachel. Wird aus den {@code @DashboardTile}-Metadaten
 * gebaut und von einem {@link ch.plaintext.boot.dashboard.DashboardTileDataProvider} mit dynamischen
 * Inhalten (Status, Info, Aktionen, Dropdown) angereichert. Die Startseite rendert daraus das
 * Kachel-Grid.
 *
 * @author plaintext.ch
 */
@Data
public class DashboardTileData implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Whitelist für eine literale CSS-Farbe: entweder ein Hex-Wert ({@code #rgb}, {@code #rrggbb}
     * oder {@code #rrggbbaa}) oder ein benannter CSS-Farbname (nur Buchstaben). Funktions-Notation
     * wie {@code rgb(...)} oder {@code url(...)} ist bewusst nicht erlaubt, damit aus dem
     * {@code statusColor}-Wert kein CSS-Kontext aufgebrochen werden kann.
     */
    private static final Pattern SAFE_COLOR =
        Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})|[a-zA-Z]{1,32}");

    /** Eindeutige technische ID der Kachel (matcht den Provider). */
    private String id;

    /** Titel/Überschrift. */
    private String title;

    /** PrimeFaces-Icon-Klasse (z. B. {@code pi pi-map}). */
    private String icon;

    /** Optionale Kopfbild-URL. */
    private String image;

    /** Dynamischer Status-Text (z. B. "Aktiver Lauf: Biel · 5 Läufer"). */
    private String statusText;

    /**
     * Farbe des Status-Indikators. <strong>Vertrag:</strong> Hier darf ausschliesslich eine
     * <em>literale</em> CSS-Farbe stehen (Hex wie {@code #3cb44b} oder ein benannter Farbname),
     * niemals Benutzer- oder Mandantendaten. Der Wert wird im JSF-{@code style}-Attribut gerendert;
     * JSF escaped zwar HTML, aber nicht den CSS-Kontext. Beim Rendern wird daher {@link #getSafeStatusColor()}
     * verwendet, das ungültige Werte verwirft.
     */
    private String statusColor;

    /** Zusätzlicher Info-/Beschreibungstext. */
    private String infoText;

    /** Aktions-Buttons der Kachel (Label + Link). */
    private List<TileAction> actions = new ArrayList<>();

    /** Optionales Dropdown-Menü der Kachel. */
    private TileDropdown dropdown;

    /** Sortierreihenfolge (kleinere Werte zuerst). */
    private int order;

    /**
     * Liefert {@link #statusColor} nur dann, wenn es eine literale CSS-Farbe ist (Hex oder benannter
     * Farbname), sonst {@code null}. Damit ist der im {@code style}-Attribut gerenderte Wert gegen
     * CSS-Injection abgesichert, falls ein Provider versehentlich keine literale Farbe liefert.
     * Die Startseite rendert {@code safeStatusColor} (mit Fallback auf eine Default-Farbe).
     *
     * @return die validierte Farbe oder {@code null}, wenn ungültig/leer
     */
    public String getSafeStatusColor() {
        if (statusColor == null) {
            return null;
        }
        String trimmed = statusColor.trim();
        return SAFE_COLOR.matcher(trimmed).matches() ? trimmed : null;
    }

    /**
     * Eine Aktion einer Kachel: ein beschriftetes Navigationsziel.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TileAction implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * Whitelist der erlaubten URL-Schemes. Relative Pfade (kein Scheme) sind ebenfalls erlaubt.
         */
        private static final Set<String> ALLOWED_LINK_SCHEMES = Set.of("http", "https", "mailto", "tel");

        /** Beschriftung des Buttons / der Option. */
        private String label;

        /**
         * Ziel-Link (z. B. {@code bieler-map.html}). <strong>Vertrag:</strong> Provider dürfen nur
         * unbedenkliche Ziele setzen (relative Pfade oder Schemes {@code http}, {@code https},
         * {@code mailto}, {@code tel}). Der Wert wird im {@code href}-Attribut gerendert; JSF escaped
         * zwar HTML, verhindert aber keine gefährlichen Schemes wie {@code javascript:}. Beim Rendern
         * wird daher {@link #getSafeLink()} verwendet, das ungültige Schemes verwirft.
         */
        private String link;

        /** Optionale Icon-Klasse. */
        private String icon;

        public TileAction(String label, String link) {
            this.label = label;
            this.link = link;
        }

        /**
         * Liefert {@link #link} nur dann, wenn es ein unbedenkliches Navigationsziel ist, sonst {@code null}.
         * Erlaubt: relative Pfade (kein Scheme), sowie die Schemes {@code http}, {@code https},
         * {@code mailto}, {@code tel}. Verworfen werden mindestens: {@code javascript:}, {@code data:},
         * {@code vbscript:}, protokoll-relative URLs ({@code //host}, Open Redirect) sowie Varianten mit
         * führendem Whitespace oder Steuerzeichen im Scheme-Teil
         * (Browser ignorieren z.B. {@code \t} in {@code java\tscript:}).
         * Defense-in-Depth analog zu {@link DashboardTileData#getSafeStatusColor()}.
         *
         * @return der validierte Link oder {@code null}, wenn gefährlich/leer
         */
        public String getSafeLink() {
            if (link == null || link.isEmpty()) {
                return null;
            }
            // Steuerzeichen und Whitespace entfernen (Browser ignorieren z.B. \t,\n im Scheme-Teil)
            StringBuilder sb = new StringBuilder(link.length());
            for (int i = 0; i < link.length(); i++) {
                char c = link.charAt(i);
                if (c > 0x20 && c != 0x7F) {
                    sb.append(c);
                }
            }
            String normalized = sb.toString();
            if (normalized.isEmpty()) {
                return null;
            }
            int colonIdx = normalized.indexOf(':');
            if (colonIdx < 0) {
                // Kein Scheme → relativer Pfad; protokoll-relative URLs (//host) ablehnen,
                // Browser interpretieren sie als absolute URL mit dem Scheme der aktuellen Seite (Open Redirect)
                if (normalized.startsWith("//")) {
                    return null;
                }
                return link;
            }
            // Scheme-Whitelist prüfen (case-insensitiv)
            String scheme = normalized.substring(0, colonIdx).toLowerCase();
            return ALLOWED_LINK_SCHEMES.contains(scheme) ? link : null;
        }
    }

    /**
     * Ein Dropdown-Menü einer Kachel mit mehreren Navigations-Optionen.
     */
    @Data
    @NoArgsConstructor
    public static class TileDropdown implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Beschriftung des Dropdown-Buttons (z. B. "Rennen wählen"). */
        private String label;

        /** Optionales Icon des Dropdown-Buttons. */
        private String icon;

        /** Auswahl-Optionen (Label + Link). */
        private List<TileAction> options = new ArrayList<>();

        public TileDropdown(String label) {
            this.label = label;
        }
    }
}

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
 * Transfer object (DTO) for a dashboard tile. It is built from the {@code @DashboardTile} metadata
 * and enriched with dynamic content (status, info, actions, dropdown) by a
 * {@link ch.plaintext.boot.dashboard.DashboardTileDataProvider}. The home page renders the tile
 * grid from it.
 *
 * @author plaintext.ch
 */
@Data
public class DashboardTileData implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Whitelist for a literal CSS color: either a hex value ({@code #rgb}, {@code #rrggbb} or
     * {@code #rrggbbaa}) or a named CSS color (letters only). Function notation such as
     * {@code rgb(...)} or {@code url(...)} is deliberately not allowed, so that the
     * {@code statusColor} value cannot break out of the CSS context.
     */
    private static final Pattern SAFE_COLOR =
        Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})|[a-zA-Z]{1,32}");

    /** Unique technical ID of the tile (matches the provider). */
    private String id;

    /** Title/heading. */
    private String title;

    /** PrimeFaces icon class (e.g. {@code pi pi-map}). */
    private String icon;

    /** Optional header image URL. */
    private String image;

    /** Dynamic status text (e.g. "Aktiver Lauf: Biel · 5 Läufer"). */
    private String statusText;

    /**
     * Color of the status indicator. <strong>Contract:</strong> only a <em>literal</em> CSS color
     * may go here (hex such as {@code #3cb44b} or a named color), never user or tenant data. The
     * value is rendered into the JSF {@code style} attribute; JSF does escape HTML, but not the
     * CSS context. Rendering therefore uses {@link #getSafeStatusColor()}, which discards invalid
     * values.
     */
    private String statusColor;

    /** Additional info/description text. */
    private String infoText;

    /** Action buttons of the tile (label + link). */
    private List<TileAction> actions = new ArrayList<>();

    /** Optional dropdown menu of the tile. */
    private TileDropdown dropdown;

    /** Sort order (lower values first). */
    private int order;

    /**
     * Returns {@link #statusColor} only if it is a literal CSS color (hex or a named color),
     * otherwise {@code null}. This protects the value rendered into the {@code style} attribute
     * against CSS injection, should a provider accidentally supply something other than a literal
     * color. The home page renders {@code safeStatusColor} (with a fallback to a default color).
     *
     * @return the validated color, or {@code null} if invalid/empty
     */
    public String getSafeStatusColor() {
        if (statusColor == null) {
            return null;
        }
        String trimmed = statusColor.trim();
        return SAFE_COLOR.matcher(trimmed).matches() ? trimmed : null;
    }

    /**
     * An action of a tile: a labeled navigation target.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TileAction implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * Whitelist of the permitted URL schemes. Relative paths (no scheme) are allowed as well.
         */
        private static final Set<String> ALLOWED_LINK_SCHEMES = Set.of("http", "https", "mailto", "tel");

        /** Label of the button / the option. */
        private String label;

        /**
         * Target link (e.g. {@code bieler-map.html}). <strong>Contract:</strong> providers may set
         * harmless targets only (relative paths, or the schemes {@code http}, {@code https},
         * {@code mailto}, {@code tel}). The value is rendered into the {@code href} attribute; JSF
         * escapes HTML but does not prevent dangerous schemes such as {@code javascript:}.
         * Rendering therefore uses {@link #getSafeLink()}, which discards invalid schemes.
         */
        private String link;

        /** Optional icon class. */
        private String icon;

        public TileAction(String label, String link) {
            this.label = label;
            this.link = link;
        }

        /**
         * Returns {@link #link} only if it is a harmless navigation target, otherwise {@code null}.
         * Allowed: relative paths (no scheme), plus the schemes {@code http}, {@code https},
         * {@code mailto}, {@code tel}. Discarded are at least: {@code javascript:}, {@code data:},
         * {@code vbscript:}, protocol-relative URLs ({@code //host}, open redirect) as well as
         * variants with leading whitespace or control characters in the scheme part
         * (browsers ignore e.g. {@code \t} in {@code java\tscript:}).
         * Defense in depth, analogous to {@link DashboardTileData#getSafeStatusColor()}.
         *
         * @return the validated link, or {@code null} if dangerous/empty
         */
        public String getSafeLink() {
            if (link == null || link.isEmpty()) {
                return null;
            }
            // Strip control characters and whitespace (browsers ignore e.g. \t,\n in the scheme part)
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
                // No scheme -> relative path; reject protocol-relative URLs (//host),
                // browsers read them as an absolute URL with the current page's scheme (open redirect)
                if (normalized.startsWith("//")) {
                    return null;
                }
                return link;
            }
            // Check the scheme whitelist (case-insensitive)
            String scheme = normalized.substring(0, colonIdx).toLowerCase();
            return ALLOWED_LINK_SCHEMES.contains(scheme) ? link : null;
        }
    }

    /**
     * A dropdown menu of a tile with several navigation options.
     */
    @Data
    @NoArgsConstructor
    public static class TileDropdown implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Label of the dropdown button (e.g. "Rennen wählen"). */
        private String label;

        /** Optional icon of the dropdown button. */
        private String icon;

        /** Selectable options (label + link). */
        private List<TileAction> options = new ArrayList<>();

        public TileDropdown(String label) {
            this.label = label;
        }
    }
}

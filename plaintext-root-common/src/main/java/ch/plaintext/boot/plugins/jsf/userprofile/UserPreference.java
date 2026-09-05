/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;
import ch.plaintext.boot.plugins.objstore.SimpleStorable;
import ch.plaintext.boot.table.TableState;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Everything a user has set up for themselves: theme, menu, language — and, per table, which
 * columns they want to see and how wide.
 *
 * <p><b>{@code ignoreUnknown} is the rollback insurance (Karte 1077).</b> The record is stored as
 * JSON by {@code SimpleStorableConverter}, whose mapper fails on unknown properties and then
 * returns {@code null} for the whole record. Every field added here is unknown to the previous
 * root version; without this annotation a rollback would hand every user who had already saved
 * the new field a blank preference set — and the next save would overwrite the old record for
 * good. The annotation only protects from this version onwards; the versions before it cannot
 * be helped retroactively.
 */
@Slf4j
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPreference implements SimpleStorable<UserPreference>, Serializable {

    private String menuMode = "layout-sidebar";

    private String darkMode = "light";

    private String componentTheme = "green";

    private String topbarTheme = "light";

    private String menuTheme = "light";

    private String inputStyle = "outlined";

    private boolean lightLogo = false;

    private boolean menuStatic = true;  // Sidebar is expanded/pinned by default

    private String customColor;  // Hex color like "#FF5733" for custom theme, null = use predefined

    private List<NamedColor> customColors = new ArrayList<>();

    private Set<String> hiddenColors = new HashSet<>();

    /**
     * Per table, the columns this user wants to see (request by Daniel, 25.08.2026:
     * "at the top I would also like a checkbox selection, so that the choice of columns can be
     * stored per user").
     *
     * <p>The key is a table identifier ({@code "useradmin"}), the value the visible column keys.
     * Deliberately a map and not a separate field per table: the next table that needs this gets
     * by without any change to this class.
     *
     * <p>A <b>missing</b> entry means "nothing has ever been selected" and has to be interpreted
     * by the respective table as its default — not as "all columns off". Storage is JSON
     * ({@code SimpleStorableConverter}), so a new field is uncritical for existing records.
     */
    private Map<String, List<String>> tabellenSpalten = new HashMap<>();

    /**
     * Null-safe getter (replaces the Lombok getter): old records that were stored BEFORE this
     * field existed come back from the XStream deserialization with {@code null} — the field
     * initializer never runs there. In guild PROD this led to a 500 on the user administration
     * on 28.08.2026 (NPE in UserPreferencesBackingBean.tabellenSpalten).
     */
    public Map<String, List<String>> getTabellenSpalten() {
        if (tabellenSpalten == null) {
            tabellenSpalten = new HashMap<>();
        }
        return tabellenSpalten;
    }

    /**
     * Karte 1077: per table, the full display state of this user — visibility, widths, named
     * profiles ({@link TableState}). This is the storage behind {@code pt:tableSettings}, written
     * and read by {@code UserPreferenceTableStateStore}.
     *
     * <p>The key is <b>{@code mandat + "/" + page}</b>, e.g. {@code "guild42/guild-member"}: the
     * same user works in several tenants, and a column choice made for one of them must not show
     * up in the other (decision 3 of Karte 1077). {@link #tabellenSpalten} has no tenant in its
     * key; it stays as it is, and the store reads it as a fallback when no entry exists here yet.
     *
     * <p>Like {@link #tabellenSpalten} a map and not a field per table: the next table costs no
     * change to this class. A missing entry means "never set up" — the table starts with its
     * own defaults.
     */
    private Map<String, TableState> tabellenStaende = new HashMap<>();

    /**
     * Null-safe getter for the same reason as {@link #getTabellenSpalten()}: records stored
     * before this field existed come back with {@code null}, the field initializer never runs
     * on deserialization.
     */
    public Map<String, TableState> getTabellenStaende() {
        if (tabellenStaende == null) {
            tabellenStaende = new HashMap<>();
        }
        return tabellenStaende;
    }

    /**
     * Karte 937: width of the wiki page tree in pixels, {@code 0} = the layout's default.
     *
     * <p><b>Why here and not in a store of app's own.</b> This is per-user layout state — exactly
     * like {@link #menuStatic} one line above. A second store for user settings would be a
     * competing pattern: whoever looks for it later would find two places and would have to guess
     * which one applies.
     *
     * <p><b>Why pixels and not percent:</b> a tree needs a minimum width for page titles to stay
     * readable; that depends on the font size, not on the window width. While loading, the UI
     * additionally clamps the value to the current window — otherwise the width set on the large
     * monitor is unusable on the notebook.
     */
    private int wikiTreeWidth = 0;

    /**
     * Karte 937: width of the mail list in pixels, {@code 0} = the layout's default.
     *
     * <p>Deliberately kept separate from the wiki value: the two views have nothing to do with each
     * other, and a shared value would move the one whenever the other is dragged.
     */
    private int mailListWidth = 0;

    private String language = "de";

    private String user = "";

    /**
     * Returns the custom colors list, initializing it if null (XStream deserialization of old data).
     */
    public List<NamedColor> getCustomColors() {
        if (customColors == null) {
            customColors = new ArrayList<>();
        }
        return customColors;
    }

    /**
     * Returns the hidden colors set, initializing it if null (XStream deserialization of old data).
     */
    public Set<String> getHiddenColors() {
        if (hiddenColors == null) {
            hiddenColors = new HashSet<>();
        }
        return hiddenColors;
    }

    @Override
    public String getUniqueId() {
        return user;
    }

    @Override
    public void setUniqueId(String id) {
        user = id;
    }

    /**
     * A named custom color with a display name and hex value.
     */
    @Data
    public static class NamedColor implements Serializable {
        private String name;
        private String hex;

        public NamedColor() {}

        public NamedColor(String name, String hex) {
            this.name = name;
            this.hex = hex;
        }
    }
}

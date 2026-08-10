/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;
import ch.plaintext.boot.plugins.objstore.SimpleStorable;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Data
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

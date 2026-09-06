/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.plugins.jsf.userprofile.UserPreferencesBackingBean;
import ch.plaintext.boot.plugins.log.Log;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreference;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPrefsSimpleStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/preferences")
@Tag(name = "User Preferences", description = "Manage user UI preferences such as theme, menu mode, and dark mode")
public class UserPreferencesRestController {

    private static final Set<String> VALID_DARK_MODES = Set.of("dark", "light");
    private static final Set<String> VALID_MENU_MODES = Set.of("layout-sidebar", "layout-overlay", "layout-slim", "layout-horizontal");
    private static final Set<String> VALID_INPUT_STYLES = Set.of("outlined", "filled");
    private static final int MAX_PARAM_LENGTH = 100;

    @Autowired
    private UserPrefsSimpleStorage storage;

    @Autowired
    private UserPreferencesBackingBean userPreferencesBackingBean;

    @Operation(summary = "Save user preferences",
               description = "Persists UI preferences (theme, dark mode, menu layout, etc.) for the currently authenticated user. "
                           + "Only provided parameters are updated; omitted parameters retain their previous values. "
                           + "The dark-mode setting is also stored as a cookie for consistent login-page theming.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preferences saved successfully"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated"),
            @ApiResponse(responseCode = "500", description = "Internal server error while saving preferences")
    })
    @PostMapping("/save")
    public ResponseEntity<String> savePreferences(
            @Parameter(description = "PrimeFaces component theme name") @RequestParam(required = false) String componentTheme,
            @Parameter(description = "Dark mode setting (e.g. 'dark' or 'light')") @RequestParam(required = false) String darkMode,
            @Parameter(description = "Menu mode (e.g. 'static', 'overlay', 'slim')") @RequestParam(required = false) String menuMode,
            @Parameter(description = "Topbar theme identifier") @RequestParam(required = false) String topbarTheme,
            @Parameter(description = "Menu theme identifier") @RequestParam(required = false) String menuTheme,
            @Parameter(description = "Input style (e.g. 'outlined', 'filled')") @RequestParam(required = false) String inputStyle,
            @Parameter(description = "Whether the menu is pinned in static mode") @RequestParam(required = false) String menuStatic,
            @Parameter(description = "Custom hex color (e.g. '#FF5733'), used when componentTheme is 'custom'") @RequestParam(required = false) String customColor,
            HttpServletResponse response) {
        try {
            log.debug("🔵 REST /api/preferences/save called with: componentTheme={}, darkMode={}, menuMode={}, topbarTheme={}, menuTheme={}, inputStyle={}, menuStatic={}, customColor={}",
                    componentTheme, darkMode, menuMode, topbarTheme, menuTheme, inputStyle, menuStatic, customColor);

            // Get current user from security context
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body("NOT_AUTHENTICATED");
            }

            String username = auth.getName();

            // Validate input parameters
            validateParam("darkMode", darkMode, VALID_DARK_MODES);
            validateParam("menuMode", menuMode, VALID_MENU_MODES);
            validateParam("inputStyle", inputStyle, VALID_INPUT_STYLES);
            validateLength("componentTheme", componentTheme);
            validateLength("topbarTheme", topbarTheme);
            validateLength("menuTheme", menuTheme);
            if (customColor != null && !customColor.isEmpty() && !customColor.matches("^#[0-9A-Fa-f]{6}$")) {
                throw new IllegalArgumentException("Invalid hex color format for 'customColor': '" + customColor + "'. Expected format: #RRGGBB");
            }

            // Load or create user prefs
            UserPreference prefs = storage.findByUniqueId(username);
            if (prefs == null) {
                prefs = new UserPreference();
                prefs.setUniqueId(username);
            }

            // Update only provided values
            if (componentTheme != null && !componentTheme.isEmpty()) {
                prefs.setComponentTheme(componentTheme);
            }
            if (darkMode != null && !darkMode.isEmpty()) {
                prefs.setDarkMode(darkMode);
            }
            if (menuMode != null && !menuMode.isEmpty()) {
                prefs.setMenuMode(menuMode);
            }
            if (topbarTheme != null && !topbarTheme.isEmpty()) {
                prefs.setTopbarTheme(topbarTheme);
            }
            if (menuTheme != null && !menuTheme.isEmpty()) {
                prefs.setMenuTheme(menuTheme);
            }
            if (inputStyle != null && !inputStyle.isEmpty()) {
                prefs.setInputStyle(inputStyle);
            }
            if (menuStatic != null && !menuStatic.isEmpty()) {
                prefs.setMenuStatic(Boolean.parseBoolean(menuStatic));
            }
            if (customColor != null) {
                // Allow empty string to clear custom color
                prefs.setCustomColor(customColor.isEmpty() ? null : customColor);
            }

            // Save to database
            storage.save(prefs);

            log.debug("✅ Saved preferences to DB for user {}: componentTheme={}, darkMode={}, menuMode={}, topbarTheme={}, menuTheme={}, inputStyle={}",
                Log.mail(username), prefs.getComponentTheme(), prefs.getDarkMode(), prefs.getMenuMode(),
                prefs.getTopbarTheme(), prefs.getMenuTheme(), prefs.getInputStyle());

            // CRITICAL: Save theme to cookie for consistent login experience
            if (darkMode != null && !darkMode.isEmpty()) {
                saveThemeToCookie(response, "plaintext-theme", darkMode);
                log.debug("🍪 Saved dark mode to cookie: {}", darkMode);
            }
            // Also save component theme to cookie for persistence across login
            if (componentTheme != null && !componentTheme.isEmpty()) {
                saveThemeToCookie(response, "plaintext-color", componentTheme);
                log.debug("🍪 Saved color theme to cookie: {}", componentTheme);
            }
            // Save custom color to cookie for persistence across login
            if (customColor != null) {
                if (customColor.isEmpty()) {
                    // Clear custom color cookie
                    saveThemeToCookie(response, "plaintext-custom-color", "");
                } else {
                    saveThemeToCookie(response, "plaintext-custom-color", customColor);
                    log.debug("🍪 Saved custom color to cookie: {}", customColor);
                }
            }

            // Also update the session-scoped UserPreferencesBackingBean
            // Otherwise, the session bean will have stale values on next page load
            try {
                if (userPreferencesBackingBean != null) {
                    userPreferencesBackingBean.updateFromRestApi(componentTheme, darkMode, menuMode,
                            topbarTheme, menuTheme, inputStyle, menuStatic, customColor);
                }
            } catch (Exception sessionEx) {
                log.debug("Session bean update skipped (DB save succeeded): {}", sessionEx.getMessage());
            }

            return ResponseEntity.ok("OK");
        } catch (IllegalArgumentException e) {
            log.warn("Invalid preferences input: {}", e.getMessage());
            return ResponseEntity.status(400).body(e.getMessage());
        } catch (Exception e) {
            log.error("Error saving preferences", e);
            return ResponseEntity.status(500).body("ERROR: Internal server error");
        }
    }

    @Operation(summary = "Add a custom named color",
               description = "Adds a new custom color with a display name to the user's palette.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Color added successfully"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/add-color")
    public ResponseEntity<String> addColor(
            @Parameter(description = "Display name for the color") @RequestParam String name,
            @Parameter(description = "Hex color value (e.g. '#FF5733')") @RequestParam String hex) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body("NOT_AUTHENTICATED");
            }

            String username = auth.getName();

            // Validate inputs
            if (name == null || name.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Name is required");
            }
            if (name.length() > MAX_PARAM_LENGTH) {
                return ResponseEntity.badRequest().body("Name too long");
            }
            if (hex == null || !hex.matches("^#[0-9A-Fa-f]{6}$")) {
                return ResponseEntity.badRequest().body("Invalid hex color format. Expected: #RRGGBB");
            }

            UserPreference prefs = storage.findByUniqueId(username);
            if (prefs == null) {
                prefs = new UserPreference();
                prefs.setUniqueId(username);
            }

            prefs.getCustomColors().add(new UserPreference.NamedColor(name.trim(), hex));
            storage.save(prefs);

            // Update session bean
            try {
                if (userPreferencesBackingBean != null) {
                    // Trigger reload by re-reading from storage
                    userPreferencesBackingBean.save();
                }
            } catch (Exception e) {
                log.debug("Session bean update skipped: {}", e.getMessage());
            }

            log.debug("Added custom color '{}' ({}) for user {}", name, hex, Log.mail(username));
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error adding custom color", e);
            return ResponseEntity.status(500).body("ERROR: Internal server error");
        }
    }

    @Operation(summary = "Delete a color from the palette",
               description = "Deletes a custom color by name or hides a predefined color.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Color deleted/hidden successfully"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/delete-color")
    public ResponseEntity<String> deleteColor(
            @Parameter(description = "Color key: custom color name or predefined theme file name") @RequestParam String colorKey) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body("NOT_AUTHENTICATED");
            }

            String username = auth.getName();

            if (colorKey == null || colorKey.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("colorKey is required");
            }

            UserPreference prefs = storage.findByUniqueId(username);
            if (prefs == null) {
                return ResponseEntity.ok("OK"); // Nothing to delete
            }

            // Check if it's a custom color (try to remove by name)
            boolean removedCustom = prefs.getCustomColors().removeIf(c -> colorKey.equals(c.getName()));

            if (!removedCustom) {
                // It's a predefined color - hide it
                prefs.getHiddenColors().add(colorKey);
            }

            storage.save(prefs);

            try {
                if (userPreferencesBackingBean != null) {
                    userPreferencesBackingBean.save();
                }
            } catch (Exception e) {
                log.debug("Session bean update skipped: {}", e.getMessage());
            }

            log.debug("Deleted/hidden color '{}' for user {}", colorKey, Log.mail(username));
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error deleting color", e);
            return ResponseEntity.status(500).body("ERROR: Internal server error");
        }
    }

    @Operation(summary = "Restore all hidden predefined colors",
               description = "Makes all hidden predefined colors visible again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Colors restored successfully"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/restore-colors")
    public ResponseEntity<String> restoreColors() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body("NOT_AUTHENTICATED");
            }

            String username = auth.getName();

            UserPreference prefs = storage.findByUniqueId(username);
            if (prefs == null) {
                return ResponseEntity.ok("OK");
            }

            prefs.getHiddenColors().clear();
            storage.save(prefs);

            try {
                if (userPreferencesBackingBean != null) {
                    userPreferencesBackingBean.save();
                }
            } catch (Exception e) {
                log.debug("Session bean update skipped: {}", e.getMessage());
            }

            log.debug("Restored all hidden colors for user {}", Log.mail(username));
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error restoring colors", e);
            return ResponseEntity.status(500).body("ERROR: Internal server error");
        }
    }

    private void validateParam(String name, String value, Set<String> allowed) {
        if (value != null && !value.isEmpty() && !allowed.contains(value)) {
            throw new IllegalArgumentException(
                    "Invalid value for '" + name + "': '" + value + "'. Allowed: " + allowed);
        }
    }

    private void validateLength(String name, String value) {
        if (value != null && value.length() > MAX_PARAM_LENGTH) {
            throw new IllegalArgumentException(
                    "Parameter '" + name + "' exceeds maximum length of " + MAX_PARAM_LENGTH);
        }
    }

    /**
     * Saves a preference value to a named cookie.
     */
    private void saveThemeToCookie(HttpServletResponse response, String cookieName, String value) {
        try {
            Cookie cookie = new Cookie(cookieName, value);
            cookie.setPath("/");
            cookie.setMaxAge(365 * 24 * 60 * 60); // 1 year
            // NOSONAR (S3330): the cookie carries exclusively the theme choice (e.g. "dark") and
            // MUST be readable by JavaScript, otherwise the page flickers in the wrong theme while
            // loading. It contains no secret and no session identifier; Secure and SameSite=Lax
            // are set (card 458).
            cookie.setHttpOnly(false); // NOSONAR — theme is read client-side via JavaScript
            cookie.setSecure(true);    // HTTPS only; harmless on http://localhost dev
            cookie.setAttribute("SameSite", "Lax");
            response.addCookie(cookie);
        } catch (Exception e) {
            log.error("Error saving cookie '{}': {}", cookieName, e.getMessage(), e);
        }
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;
import jakarta.annotation.PostConstruct;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.primefaces.PrimeFaces;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Session-scoped bean for managing user preferences with PrimeFaces integration.
 * Refactored to use composition instead of duplicating fields from UserPreference.
 *
 * REFACTORED: Removed field duplication - now delegates to UserPreference instance.
 * Single source of truth: all preference data stored in 'prefs' field.
 */
@Slf4j
@Scope(value = "session", proxyMode = ScopedProxyMode.TARGET_CLASS)
@Component("userPreferencesBackingBean")
public class UserPreferencesBackingBean implements Serializable {

    private static final String LAYOUT_MENU = "layout-menu";
    private static final String LAYOUT_TOPBAR = "layout-topbar";
    private static final String LAYOUT_HORIZONTAL = "layout-horizontal";

    @Autowired
    private transient UserPrefsSimpleStorage storage;

    /**
     * Single source of truth for all user preference data.
     * Replaces previous field duplication.
     * Marked transient as UserPreference is not Serializable.
     */
    private transient UserPreference prefs;

    @Getter
    @Setter
    private List<ComponentTheme> componentThemes = new ArrayList<>();

    @PostConstruct
    public void init() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();
        prefs = storage.findByUniqueId(username);

        // Load theme from cookie if available (for seamless login experience)
        String cookieTheme = loadThemeFromCookie();

        // Load color theme from cookie if available
        String cookieColor = loadCookieValue("plaintext-color");

        // Load custom color from cookie if available
        String cookieCustomColor = loadCookieValue("plaintext-custom-color");

        if (prefs != null) {
            log.info("PREFS-INIT user={} componentTheme={} darkMode={} customColor={} cookieColor={} cookieCustomColor={}",
                    username, prefs.getComponentTheme(), prefs.getDarkMode(), prefs.getCustomColor(), cookieColor, cookieCustomColor);

            // If cookie theme differs from DB, update DB to match cookie
            if (cookieTheme != null && !cookieTheme.equals(prefs.getDarkMode())) {
                log.debug("Cookie theme '{}' differs from DB '{}', updating DB to match cookie", cookieTheme, prefs.getDarkMode());
                prefs.setDarkMode(cookieTheme);
                prefs.setTopbarTheme(cookieTheme);
                prefs.setMenuTheme(cookieTheme);
                prefs.setLightLogo(!cookieTheme.equals("light"));
            }

            boolean needsSync = false;

            // If color cookie differs from DB, update DB to match cookie
            if (cookieColor != null && !cookieColor.equals(prefs.getComponentTheme())) {
                log.debug("Cookie color '{}' differs from DB '{}', updating DB to match cookie", cookieColor, prefs.getComponentTheme());
                prefs.setComponentTheme(cookieColor);
                needsSync = true;
            }

            // If custom color cookie differs from DB, update DB to match cookie
            if (cookieCustomColor != null && !cookieCustomColor.equals(prefs.getCustomColor())) {
                log.debug("Cookie custom color '{}' differs from DB '{}', updating DB to match cookie", cookieCustomColor, prefs.getCustomColor());
                prefs.setCustomColor(cookieCustomColor);
                needsSync = true;
            }

            // Ensure themes are consistent with darkMode
            // Topbar and menu themes must match darkMode to render correctly
            if (!prefs.getTopbarTheme().equals(prefs.getDarkMode())) {
                log.debug("Syncing topbarTheme from '{}' to '{}' to match darkMode", prefs.getTopbarTheme(), prefs.getDarkMode());
                prefs.setTopbarTheme(prefs.getDarkMode());
                needsSync = true;
            }
            if (!prefs.getMenuTheme().equals(prefs.getDarkMode())) {
                log.debug("Syncing menuTheme from '{}' to '{}' to match darkMode", prefs.getMenuTheme(), prefs.getDarkMode());
                prefs.setMenuTheme(prefs.getDarkMode());
                needsSync = true;
            }
            // Migration: Set menuStatic to true if it was false (old default)
            if (!prefs.isMenuStatic()) {
                log.debug("Migrating menuStatic from false to true (new default: sidebar expanded)");
                prefs.setMenuStatic(true);
                needsSync = true;
            }
            if (needsSync) {
                save(); // Persist the corrected values
            }
        } else {
            prefs = new UserPreference();
            prefs.setUniqueId(username);
            save();
        }

        componentThemes.add(new ComponentTheme("Blue", "blue", "#2196F3"));
        componentThemes.add(new ComponentTheme("Green", "green", "#4CAF50"));
        componentThemes.add(new ComponentTheme("Orange", "orange", "#FF9800"));
        componentThemes.add(new ComponentTheme("Turquoise", "turquoise", "#00BCD4"));
        componentThemes.add(new ComponentTheme("Avocado", "avocado", "#AEC523"));
        componentThemes.add(new ComponentTheme("Purple", "purple", "#7B1FA2"));
        componentThemes.add(new ComponentTheme("Red", "red", "#F44336"));
        componentThemes.add(new ComponentTheme("Yellow", "yellow", "#FFC107"));
        componentThemes.add(new ComponentTheme("Lime", "lime", "#8BC34A"));
        componentThemes.add(new ComponentTheme("Crimson", "crimson", "#B71C1C"));
    }

    /**
     * Save preferences with optimistic locking retry.
     * No longer synchronized — uses JPA @Version for concurrency control.
     */
    public void save() {
        try {
            storage.save(prefs);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            log.debug("Optimistic lock conflict on preferences save, retrying...");
            try {
                // Reload and re-apply
                UserPreference fresh = storage.findByUniqueId(prefs.getUniqueId());
                if (fresh != null) {
                    prefs = fresh;
                }
                storage.save(prefs);
            } catch (Exception retryEx) {
                log.error("Retry failed for preferences save: " + retryEx.getMessage(), retryEx);
            }
        } catch (Exception e) {
            log.error("Error saving user preferences: " + e.getMessage(), e);
        }
    }

    /**
     * Update fields directly from REST API without triggering PrimeFaces scripts.
     * This is used when the REST API saves preferences - we need to update the
     * session-scoped bean but can't call the normal setters (which use PrimeFaces).
     */
    public void updateFromRestApi(String componentTheme, String darkMode, String menuMode,
                                   String topbarTheme, String menuTheme, String inputStyle,
                                   String menuStatic, String customColor) {
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
        log.debug("✅ Session bean updated via REST API");
    }

    // ==================== Custom Color ====================

    public String getCustomColor() {
        return prefs.getCustomColor();
    }

    public void setCustomColor(String customColor) {
        prefs.setCustomColor(customColor);
        save();
    }

    // ==================== Color Palette ====================

    /**
     * Returns predefined themes filtered by the user's hidden colors set.
     */
    public List<ComponentTheme> getVisibleComponentThemes() {
        java.util.Set<String> hidden = prefs.getHiddenColors();
        if (hidden == null || hidden.isEmpty()) {
            return componentThemes;
        }
        List<ComponentTheme> visible = new ArrayList<>();
        for (ComponentTheme theme : componentThemes) {
            if (!hidden.contains(theme.getFile())) {
                visible.add(theme);
            }
        }
        return visible;
    }

    /**
     * Returns the user's custom named colors.
     */
    public List<UserPreference.NamedColor> getCustomColors() {
        return prefs.getCustomColors();
    }

    /**
     * Returns the set of hidden predefined color names.
     */
    public java.util.Set<String> getHiddenColors() {
        return prefs.getHiddenColors();
    }

    /**
     * Returns true if any predefined colors are hidden.
     */
    public boolean isHasHiddenColors() {
        java.util.Set<String> hidden = prefs.getHiddenColors();
        return hidden != null && !hidden.isEmpty();
    }

    // ==================== Delegating Getters ====================

    public String getDarkMode() {
        return prefs.getDarkMode();
    }

    public String getDarkMode2() {
        return prefs.getDarkMode();
    }

    public boolean isLightLogo() {
        return prefs.isLightLogo();
    }

    public String getComponentTheme() {
        return prefs.getComponentTheme();
    }

    public String getMenuTheme() {
        return prefs.getMenuTheme();
    }

    public String getTopbarTheme() {
        return prefs.getTopbarTheme();
    }

    public String getMenuMode() {
        return prefs.getMenuMode();
    }

    public String getInputStyle() {
        return prefs.getInputStyle();
    }

    public boolean isMenuStatic() {
        return prefs.isMenuStatic();
    }

    // ==================== Language ====================

    public String getLanguage() {
        return prefs.getLanguage();
    }

    public void setLanguage(String language) {
        prefs.setLanguage(language);
        save();
    }

    // ==================== Computed Properties ====================

    public String getLayout() {
        // Nur die tatsächlich vorhandenen Layout-CSS referenzieren (layout-light.css / layout-dark.css).
        // darkMode wird u.a. ungeprüft aus einem theme-Cookie übernommen; ein abweichender Wert
        // (z.B. "auto"/leer) ergab sonst 'css/layout-<wert>.css' -> RES_NOT_FOUND -> der Browser
        // verwirft das Stylesheet (strict MIME, application/json) und das Layout/die Karten brechen.
        return "dark".equalsIgnoreCase(prefs.getDarkMode()) ? "layout-dark" : "layout-light";
    }

    public String getTheme() {
        return prefs.getComponentTheme() + '-' + prefs.getDarkMode();
    }

    public String getInputStyleClass() {
        return prefs.getInputStyle().equals("filled") ? "ui-input-filled" : "";
    }

    public String getMenuStaticClass() {
        // Only apply layout-static for sidebar mode
        // For horizontal/slim, layout-static doesn't make sense and causes flickering
        if ("layout-sidebar".equals(prefs.getMenuMode()) && prefs.isMenuStatic()) {
            return "layout-static";
        }
        return "";
    }

    // ==================== Setters with PrimeFaces Integration ====================

    public void setDarkMode2(String darkMode) {
        prefs.setDarkMode(darkMode);
        prefs.setMenuTheme(darkMode);
        prefs.setTopbarTheme(darkMode);
        prefs.setLightLogo(!darkMode.equals("light"));
        // Update data-theme attribute on HTML element to prevent flash
        PrimeFaces.current().executeScript("document.documentElement.setAttribute('data-theme', '" + darkMode + "')");
        // Save theme to cookie for consistent login experience
        saveThemeToCookie(darkMode);
        save();
    }

    public void toggleDarkMode() {
        String newTheme = "light".equals(prefs.getDarkMode()) ? "dark" : "light";
        setDarkMode2(newTheme);
    }

    /**
     * Loads the dark mode theme from cookie if available.
     * @return theme value from cookie or null if not found
     */
    private String loadThemeFromCookie() {
        String value = loadCookieValue("plaintext-theme");
        if (value != null && ("light".equals(value) || "dark".equals(value))) {
            return value;
        }
        return null;
    }

    /**
     * Loads a named cookie value.
     * @return cookie value or null if not found
     */
    private String loadCookieValue(String cookieName) {
        try {
            FacesContext context = FacesContext.getCurrentInstance();
            if (context == null) {
                return null;
            }

            ExternalContext externalContext = context.getExternalContext();
            HttpServletRequest request = (HttpServletRequest) externalContext.getRequest();

            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (cookieName.equals(cookie.getName())) {
                        String value = cookie.getValue();
                        if (value != null && !value.isEmpty()) {
                            log.debug("Loaded cookie '{}': {}", cookieName, value);
                            return value;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error loading cookie '{}': {}", cookieName, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Saves the current theme to a cookie.
     */
    private void saveThemeToCookie(String theme) {
        try {
            FacesContext context = FacesContext.getCurrentInstance();
            if (context == null) {
                return;
            }

            ExternalContext externalContext = context.getExternalContext();
            HttpServletResponse response = (HttpServletResponse) externalContext.getResponse();

            Cookie cookie = new Cookie("plaintext-theme", theme);
            cookie.setPath("/");
            cookie.setMaxAge(365 * 24 * 60 * 60); // 1 year
            // NOSONAR (S3330): Das Cookie traegt ausschliesslich die Themewahl (z.B. "dark") und
            // MUSS fuer JavaScript lesbar sein, sonst flackert die Seite beim Laden im falschen
            // Theme. Es enthaelt kein Geheimnis und keine Sitzungskennung; Secure und SameSite=Lax
            // sind gesetzt (Karte 458).
            cookie.setHttpOnly(false); // NOSONAR — theme is read client-side via JavaScript
            cookie.setSecure(true);    // HTTPS only; harmless on http://localhost dev
            cookie.setAttribute("SameSite", "Lax");
            response.addCookie(cookie);
            log.debug("Saved theme to cookie: {}", theme);
        } catch (Exception e) {
            log.error("Error saving theme to cookie: " + e.getMessage(), e);
        }
    }

    public void setComponentTheme(String componentTheme) {
        prefs.setComponentTheme(componentTheme);
        save();
    }

    public void setMenuTheme(String menuTheme) {
        prefs.setMenuTheme(menuTheme);
        PrimeFaces.current().executeScript("Plaintext.Configurator.changeSectionTheme('" + menuTheme + "' , '" + LAYOUT_MENU + "')");
        save();
    }

    public void setTopbarTheme(String topbarTheme) {
        prefs.setTopbarTheme(topbarTheme);
        prefs.setLightLogo(!topbarTheme.equals("light"));

        PrimeFaces.current().executeScript("Plaintext.Configurator.changeSectionTheme('" + topbarTheme + "' , '" + LAYOUT_TOPBAR + "')");
        if (LAYOUT_HORIZONTAL.equals(prefs.getMenuMode())) {
            PrimeFaces.current().executeScript("Plaintext.Configurator.changeSectionTheme('" + topbarTheme + "' , '" + LAYOUT_MENU + "')");
        }
        save();
    }

    public void setMenuMode(String menuMode) {
        prefs.setMenuMode(menuMode);
        if (LAYOUT_HORIZONTAL.equals(menuMode)) {
            prefs.setMenuTheme(prefs.getTopbarTheme());
            PrimeFaces.current().executeScript("Plaintext.Configurator.changeSectionTheme('" + prefs.getMenuTheme() + "' , '" + LAYOUT_MENU + "')");
        }
        PrimeFaces.current().executeScript("Plaintext.Configurator.changeMenuMode('" + menuMode + "')");
        save();
    }

    public void setInputStyle(String inputStyle) {
        prefs.setInputStyle(inputStyle);
        PrimeFaces.current().executeScript("Plaintext.Configurator.updateInputStyle('" + inputStyle + "')");
        save();
    }

    public void setMenuStatic(boolean menuStatic) {
        prefs.setMenuStatic(menuStatic);
        save();
    }

    public void toggleMenuStatic() {
        prefs.setMenuStatic(!prefs.isMenuStatic());
        save();
    }

    public void onMenuTypeChange() {
        if (LAYOUT_HORIZONTAL.equals(prefs.getMenuMode())) {
            prefs.setMenuTheme(prefs.getTopbarTheme());
            PrimeFaces.current().executeScript("Plaintext.Configurator.changeSectionTheme('" + prefs.getMenuTheme() + "' , '" + LAYOUT_MENU + "')");
        }
        save();
    }

    // ==================== Inner Class ====================

    public static class ComponentTheme implements Serializable {
        String name;
        String file;
        String color;

        public ComponentTheme(String name, String file, String color) {
            this.name = name;
            this.file = file;
            this.color = color;
        }

        public String getName() {
            return this.name;
        }

        public String getFile() {
            return this.file;
        }

        public String getColor() {
            return this.color;
        }
    }

}

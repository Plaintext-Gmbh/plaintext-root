/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;
import ch.plaintext.boot.table.TableState;
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
     * Karte 937: remember the width of a draggable splitter.
     *
     * <p><b>Why this method sits here instead of the UI touching the field itself.</b> Setting and
     * saving belong together — a value that is set without {@code save()} is gone at the next
     * login, and that would be exactly the kind of bug nobody notices, because it only shows up
     * the following day. It also puts the range check in ONE place instead of in every calling
     * page.
     *
     * <p><b>The lower bound is the actual point of the check.</b> Without it a tree can be dragged
     * down to 0 pixels — after which the handle can no longer be grabbed, and because the value is
     * persisted, the user locks themselves out for good. A value of {@code 0}, by contrast, stays
     * explicitly allowed: it means "the layout's default" and is the way back.
     *
     * @param bereich {@code "wiki"} or {@code "mail"} — unknown areas are ignored instead of
     *                throwing an exception into an Ajax response
     * @param breite  pixels; 0 resets to the layout default
     */
    /**
     * Request by Daniel, 25.08.2026: remember a table's column selection per user.
     *
     * <p>Built like {@link #merkeTrennerBreite(String, int)} and for the same reason: setting and
     * saving belong together. A selection that only sits in the field is gone at the next login —
     * the bug only shows up the following day, and by then nobody looks here any more.
     *
     * <p>An <b>empty</b> selection is stored like any other: "I want to see none of these columns"
     * is a valid statement and must not silently fall back to the default. That is why
     * {@link #tabellenSpalten(String)} distinguishes "empty" from "never set" ({@code null}).
     *
     * @param tabelle identifier of the table, e.g. {@code "useradmin"}
     * @param spalten the visible column keys; {@code null} is treated as empty
     */
    public void merkeTabellenSpalten(String tabelle, List<String> spalten) {
        if (prefs == null || tabelle == null || tabelle.isBlank()) {
            log.debug("Spaltenauswahl nicht gespeichert (Tabelle '{}', Einstellungen geladen: {})",
                    tabelle, prefs != null);
            return;
        }
        prefs.getTabellenSpalten().put(tabelle,
                spalten == null ? new ArrayList<>() : new ArrayList<>(spalten));
        save();
    }

    /**
     * The remembered column selection of a table.
     *
     * <p>Counterpart to {@link #merkeTabellenSpalten(String, List)}. A return value of
     * {@code null} explicitly means <b>"never set"</b> and must be distinguished from an empty
     * list: the calling table has to interpret {@code null} as its own default, but an empty list
     * as a deliberate choice by the user.
     *
     * @return the stored column keys or {@code null}
     */
    public List<String> tabellenSpalten(String tabelle) {
        if (prefs == null || tabelle == null) {
            // After a session has been restored, the transient field is empty.
            return null;
        }
        return prefs.getTabellenSpalten().get(tabelle);
    }

    /**
     * Karte 1077: the full display state of a table ({@link TableState}) — the storage behind
     * {@code pt:tableSettings}. Counterpart to {@link #tabellenStand(String)}; built like
     * {@link #merkeTabellenSpalten(String, List)} and for the same reason: setting and saving
     * belong together.
     *
     * <p>The caller ({@code UserPreferenceTableStateStore}) hands in the <b>complete</b> key,
     * tenant included; this class does not know which tenant is active.
     *
     * @param schluessel key of the table state, e.g. {@code "guild42/guild-member"}
     * @param stand      the state to remember; {@code null} removes the entry
     */
    public void merkeTabellenStand(String schluessel, TableState stand) {
        if (prefs == null || schluessel == null || schluessel.isBlank()) {
            log.debug("Tabellenstand nicht gespeichert (Schluessel '{}', Einstellungen geladen: {})",
                    schluessel, prefs != null);
            return;
        }
        if (stand == null) {
            prefs.getTabellenStaende().remove(schluessel);
        } else {
            prefs.getTabellenStaende().put(schluessel, stand);
        }
        save();
    }

    /**
     * The remembered display state of a table, or {@code null} for "never set up" — the caller
     * then starts with the table's defaults (and, in the store, with the fallback to
     * {@link #tabellenSpalten(String)}).
     *
     * <p>Returns the stored instance itself, not a copy: {@code TableSettings} keeps working on
     * it and hands it back to {@link #merkeTabellenStand(String, TableState)} after every change.
     * A copy would only add a place where two states could drift apart.
     *
     * @param schluessel key of the table state, tenant included
     * @return the stored state or {@code null}
     */
    public TableState tabellenStand(String schluessel) {
        if (prefs == null || schluessel == null) {
            // After a session has been restored, the transient field is empty.
            return null;
        }
        return prefs.getTabellenStaende().get(schluessel);
    }

    public void merkeTrennerBreite(String bereich, int breite) {
        int wert = breite <= 0 ? 0 : Math.clamp(breite, MIN_TRENNER_PX, MAX_TRENNER_PX);
        if ("wiki".equals(bereich)) {
            prefs.setWikiTreeWidth(wert);
        } else if ("mail".equals(bereich)) {
            prefs.setMailListWidth(wert);
        } else {
            log.debug("Unbekannter Trenner-Bereich '{}' — ignoriert", bereich);
            return;
        }
        save();
    }

    /**
     * Karte 937: the remembered width of a draggable splitter, {@code 0} = the layout's default.
     *
     * <p><b>Why this method is needed and the page does not read {@code prefs}.</b> The field is
     * private and {@code transient} and therefore unreachable from an EL expression. A page that
     * tries anyway does not fail at compile time but only while rendering — with an
     * {@code ELException} in the middle of a response that has already been sent: the menu is
     * already in place, the content is missing, and what is left in the browser is a white area
     * without any error message. That is exactly how wiki.xhtml failed from 19.08.2026 on.
     *
     * <p>Counterpart to {@link #merkeTrennerBreite(String, int)}: reading and writing share the
     * same area names, so the mapping cannot drift apart in two places.
     *
     * @param bereich {@code "wiki"} or {@code "mail"}
     * @return the remembered width in pixels; {@code 0} for unknown areas and for as long as no
     *         settings are loaded — for the page both mean "use your own default"
     */
    public int trennerBreite(String bereich) {
        if (prefs == null) {
            // After a session has been restored the transient field is empty: better the
            // layout default than an exception thrown out of an attribute.
            return 0;
        }
        if ("wiki".equals(bereich)) {
            return prefs.getWikiTreeWidth();
        }
        if ("mail".equals(bereich)) {
            return prefs.getMailListWidth();
        }
        log.debug("Unbekannter Trenner-Bereich '{}' — Vorgabe", bereich);
        return 0;
    }

    /** Lower bound: below this the handle can no longer be hit. */
    public static final int MIN_TRENNER_PX = 140;

    /** Upper bound: above this nothing is left for the content. */
    public static final int MAX_TRENNER_PX = 900;

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
        // Only reference the layout CSS files that actually exist (layout-light.css / layout-dark.css).
        // darkMode is, among other things, taken unchecked from a theme cookie; a deviating value
        // (e.g. "auto"/empty) otherwise produced 'css/layout-<value>.css' -> RES_NOT_FOUND -> the browser
        // discards the stylesheet (strict MIME, application/json) and the layout/the cards break.
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
                            // SECURITY (forensics 23.08.2026): log the name only. This method
                            // reads an ARBITRARY cookie name — anyone who ever points it at a
                            // session or remember-me cookie would otherwise have its value in the log.
                            log.debug("Loaded cookie '{}' ({} Zeichen)", cookieName, value.length());
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
            // NOSONAR (S3330): the cookie carries nothing but the theme choice (e.g. "dark") and
            // MUST be readable by JavaScript, otherwise the page flickers in the wrong theme while
            // loading. It contains no secret and no session identifier; Secure and SameSite=Lax
            // are set (Karte 458).
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

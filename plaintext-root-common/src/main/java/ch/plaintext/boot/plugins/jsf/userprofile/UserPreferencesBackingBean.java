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
     * Karte 937: Breite eines verschiebbaren Trenners merken.
     *
     * <p><b>Warum diese Methode hier steht und die Oberflaeche nicht selbst am Feld dreht.</b> Das
     * Setzen und das Speichern gehoeren zusammen — ein gesetzter Wert ohne {@code save()} ist beim
     * naechsten Anmelden weg, und genau das waere der Fehler, den niemand bemerkt, weil er erst
     * am naechsten Tag auffaellt. Ausserdem liegt die Bereichspruefung damit an EINER Stelle statt
     * in jeder aufrufenden Seite.
     *
     * <p><b>Die untere Grenze ist der eigentliche Zweck der Pruefung.</b> Ohne sie laesst sich ein
     * Baum auf 0 Pixel ziehen — danach ist der Griff nicht mehr zu fassen, und weil der Wert
     * gespeichert wird, sperrt sich der Benutzer dauerhaft aus. Ein Wert von {@code 0} bleibt
     * dagegen ausdruecklich erlaubt: er bedeutet „Vorgabe des Layouts" und ist der Weg zurueck.
     *
     * @param bereich {@code "wiki"} oder {@code "mail"} — unbekannte Bereiche werden ignoriert,
     *                statt eine Ausnahme in eine Ajax-Antwort zu werfen
     * @param breite  Pixel; 0 setzt auf die Layout-Vorgabe zurueck
     */
    /**
     * Auftrag Daniel, 25.08.2026: die Spaltenauswahl einer Tabelle je Benutzer merken.
     *
     * <p>Gebaut wie {@link #merkeTrennerBreite(String, int)} und aus demselben Grund: Setzen und
     * Speichern gehoeren zusammen. Eine Auswahl, die nur im Feld steht, ist beim naechsten
     * Anmelden weg — der Fehler faellt erst am naechsten Tag auf, und dann sucht niemand mehr
     * hier.
     *
     * <p>Eine <b>leere</b> Auswahl wird gespeichert wie jede andere: „ich will keine dieser
     * Spalten sehen" ist eine gueltige Aussage und darf nicht stillschweigend in die
     * Voreinstellung zurueckfallen. Deshalb unterscheidet {@link #tabellenSpalten(String)}
     * zwischen „leer" und „nie gesetzt" ({@code null}).
     *
     * @param tabelle Kennung der Tabelle, z.B. {@code "useradmin"}
     * @param spalten die sichtbaren Spaltenschluessel; {@code null} wird als leer behandelt
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
     * Die gemerkte Spaltenauswahl einer Tabelle.
     *
     * <p>Gegenstueck zu {@link #merkeTabellenSpalten(String, List)}. Der Rueckgabewert
     * {@code null} heisst ausdruecklich <b>„nie gesetzt"</b> und ist von einer leeren Liste zu
     * unterscheiden: die aufrufende Tabelle muss {@code null} als ihre eigene Voreinstellung
     * auslegen, eine leere Liste dagegen als bewusste Auswahl des Benutzers.
     *
     * @return die gespeicherten Spaltenschluessel oder {@code null}
     */
    public List<String> tabellenSpalten(String tabelle) {
        if (prefs == null || tabelle == null) {
            // Nach dem Wiederherstellen einer Sitzung ist das transiente Feld leer.
            return null;
        }
        return prefs.getTabellenSpalten().get(tabelle);
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
     * Karte 937: Die gemerkte Breite eines verschiebbaren Trenners, {@code 0} = Vorgabe des Layouts.
     *
     * <p><b>Warum es diese Methode braucht und die Seite nicht {@code prefs} liest.</b> Das Feld ist
     * privat und {@code transient} und damit aus einem EL-Ausdruck nicht erreichbar. Eine Seite, die
     * es trotzdem versucht, faellt nicht beim Uebersetzen auf, sondern erst beim Rendern — mit einer
     * {@code ELException} mitten in der bereits gesendeten Antwort: Das Menue steht dann schon, der
     * Inhalt fehlt, und im Browser bleibt eine weisse Flaeche ohne Fehlermeldung zurueck. Genau so
     * ist wiki.xhtml ab dem 19.08.2026 ausgefallen.
     *
     * <p>Gegenstueck zu {@link #merkeTrennerBreite(String, int)}: Lesen und Schreiben teilen sich
     * dieselben Bereichsnamen, damit die Zuordnung nicht an zwei Stellen auseinanderlaufen kann.
     *
     * @param bereich {@code "wiki"} oder {@code "mail"}
     * @return die gemerkte Breite in Pixeln; {@code 0} fuer unbekannte Bereiche und solange keine
     *         Einstellungen geladen sind — beides heisst fuer die Seite „nimm deine Vorgabe"
     */
    public int trennerBreite(String bereich) {
        if (prefs == null) {
            // Nach dem Wiederherstellen einer Sitzung ist das transiente Feld leer: lieber die
            // Layout-Vorgabe als eine Ausnahme aus einem Attribut heraus.
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

    /** Untere Grenze: darunter ist der Griff nicht mehr zu treffen. */
    public static final int MIN_TRENNER_PX = 140;

    /** Obere Grenze: darueber bleibt fuer den Inhalt nichts uebrig. */
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
                            // SECURITY (Forensik 23.08.2026): nur den Namen protokollieren. Diese Methode
                            // liest einen BELIEBIGEN Cookie-Namen — wer sie einmal auf einen
                            // Sitzungs-/Remember-Me-Cookie ansetzt, haette dessen Wert sonst im Log.
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

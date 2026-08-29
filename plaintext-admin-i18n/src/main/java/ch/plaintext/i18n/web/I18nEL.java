/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.web;

import ch.plaintext.I18nProvider;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreferencesBackingBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Application-scoped CDI bean for i18n translations in XHTML pages.
 * <p>
 * Usage in XHTML:
 * <pre>
 *   #{i18n.t('Speichern')}         - translates "Speichern" to the current user's language
 *   #{i18n.t('Speichern', 'en')}   - translates "Speichern" to English
 * </pre>
 * <p>
 * This bean is the recommended way to internationalize XHTML pages in plaintext-root
 * and all child projects. Simply replace hardcoded German text like:
 * <pre>
 *   value="Speichern"
 * </pre>
 * with:
 * <pre>
 *   value="#{i18n.t('Speichern')}"
 * </pre>
 *
 * <p><b>Kopplung an {@link UserPreferencesBackingBean} (plaintext-root-common).</b> Die Sprache des
 * Benutzers liegt in dessen session-scoped Einstellungen. Bis zum Zustandsbericht 29.08.2026 holte
 * diese Klasse sie per Reflection ({@code getClass().getMethod("getLanguage")}) aus einem
 * {@code Object}-Feld — bei <em>jedem</em> {@code i18n.t()}-Aufruf, also hunderte Male pro
 * Seitenaufbau, mit der Begruendung, eine Modulabhaengigkeit zu vermeiden. Die gab es nicht:
 * plaintext-admin-i18n haengt an plaintext-root-common, wo die Bean wohnt. Jetzt wird der
 * Scoped-Proxy ({@code ScopedProxyMode.TARGET_CLASS}) typsicher injiziert; ausserhalb einer
 * HTTP-Session (Cron, REST, Tests ohne Web-Kontext) wirft der Proxy beim Zugriff, das faengt
 * {@link #resolveUserLanguage()} ab und faellt auf Deutsch zurueck.
 *
 * @author plaintext.ch
 * @since 1.67.0
 */
@Named("i18n")
@ApplicationScoped
@Slf4j
public class I18nEL {

    /** Sprachcode, auf den alles zurueckfaellt — die Vorgabetexte in den Views sind deutsch. */
    static final String DEFAULT_LANGUAGE = "de";

    @Autowired(required = false)
    private I18nProvider i18nProvider;

    /**
     * Session-scoped Einstellungen des Benutzers, als Scoped-Proxy. {@code required = false}, weil
     * Kontexte ohne die Bean (Modultests, Konsolen-Laeufe) diese Klasse trotzdem laden duerfen —
     * dann bleibt es bei Deutsch.
     */
    @Autowired(required = false)
    private UserPreferencesBackingBean userPreferences;

    /**
     * Translates a default German label to the current user's preferred language.
     * <p>
     * If no I18nProvider is available, or i18n is disabled, or no user is logged in,
     * the original defaultGerman text is returned unchanged.
     *
     * @param defaultGerman the default label text (in German)
     * @return the translated text, or the original if no translation is available
     */
    public String t(String defaultGerman) {
        if (defaultGerman == null || defaultGerman.isBlank()) {
            return defaultGerman;
        }

        if (i18nProvider == null || !i18nProvider.isI18nEnabled()) {
            return defaultGerman;
        }

        String language = resolveUserLanguage();
        if (DEFAULT_LANGUAGE.equalsIgnoreCase(language)) {
            return defaultGerman;
        }

        return i18nProvider.translate(defaultGerman, language);
    }

    /**
     * Translates a default German label to a specific target language.
     *
     * @param defaultGerman the default label text (in German)
     * @param targetLanguage the ISO language code (e.g., "en", "fr", "it")
     * @return the translated text, or the original if no translation is available
     */
    public String t(String defaultGerman, String targetLanguage) {
        if (defaultGerman == null || defaultGerman.isBlank()) {
            return defaultGerman;
        }

        if (i18nProvider == null || !i18nProvider.isI18nEnabled()) {
            return defaultGerman;
        }

        if (targetLanguage == null || DEFAULT_LANGUAGE.equalsIgnoreCase(targetLanguage)) {
            return defaultGerman;
        }

        return i18nProvider.translate(defaultGerman, targetLanguage);
    }

    /**
     * Sprache des angemeldeten Benutzers aus {@link UserPreferencesBackingBean}; Deutsch, wenn keine
     * Bean, keine Session oder keine Sprache gesetzt ist.
     */
    String resolveUserLanguage() {
        if (userPreferences == null) {
            return DEFAULT_LANGUAGE;
        }
        try {
            String language = userPreferences.getLanguage();
            if (language != null && !language.isBlank()) {
                return language;
            }
        } catch (RuntimeException e) {
            // Scoped-Proxy ohne aktive Session (BeanCreationException/ScopeNotActiveException) oder
            // Einstellungen noch nicht geladen — kein Fehler, nur keine Sprachwahl.
            log.debug("Could not resolve user language from UserPreferencesBackingBean: {}", e.toString());
        }
        return DEFAULT_LANGUAGE;
    }
}

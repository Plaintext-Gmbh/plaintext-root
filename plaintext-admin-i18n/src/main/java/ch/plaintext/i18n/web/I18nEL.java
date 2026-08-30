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
 * <p><b>Coupling to {@link UserPreferencesBackingBean} (plaintext-root-common).</b> The user's
 * language lives in their session-scoped settings. Until the status report of 29.08.2026 this
 * class fetched it by reflection ({@code getClass().getMethod("getLanguage")}) from an
 * {@code Object} field — on <em>every</em> {@code i18n.t()} call, that is hundreds of times per
 * page build, justified by avoiding a module dependency. There was none:
 * plaintext-admin-i18n depends on plaintext-root-common, where the bean lives. Now the
 * scoped proxy ({@code ScopedProxyMode.TARGET_CLASS}) is injected type-safely; outside an
 * HTTP session (cron, REST, tests without a web context) the proxy throws on access, which
 * {@link #resolveUserLanguage()} catches before falling back to German.
 *
 * @author plaintext.ch
 * @since 1.67.0
 */
@Named("i18n")
@ApplicationScoped
@Slf4j
public class I18nEL {

    /** Language code that everything falls back to — the default texts in the views are German. */
    static final String DEFAULT_LANGUAGE = "de";

    @Autowired(required = false)
    private I18nProvider i18nProvider;

    /**
     * The user's session-scoped settings, as a scoped proxy. {@code required = false}, because
     * contexts without the bean (module tests, console runs) may still load this class —
     * German is then kept.
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
     * Language of the logged-in user from {@link UserPreferencesBackingBean}; German when there is no
     * bean, no session or no language set.
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
            // Scoped proxy without an active session (BeanCreationException/ScopeNotActiveException) or
            // settings not loaded yet — not an error, just no language choice.
            log.debug("Could not resolve user language from UserPreferencesBackingBean: {}", e.toString());
        }
        return DEFAULT_LANGUAGE;
    }
}

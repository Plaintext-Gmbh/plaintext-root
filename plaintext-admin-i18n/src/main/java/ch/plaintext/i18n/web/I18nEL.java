/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.web;

import ch.plaintext.I18nProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Application-scoped CDI bean for i18n translations in XHTML pages.
 * <p>
 * Usage in XHTML:
 * <pre>
 *   #{i18n.t('Speichern')}         - translates "Speichern" to the current user's language
 *   #{i18n.t('Speichern', 'en')}   - translates "Speichern" to English
 * </pre>
 * <p>
 * The current user's language is resolved from the session-scoped
 * UserPreferencesBackingBean (which stores the preferred language).
 * If no user is logged in or no language is set, the default German label is returned.
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
 * @author plaintext.ch
 * @since 1.67.0
 */
@Named("i18n")
@ApplicationScoped
@Slf4j
public class I18nEL {

    @Autowired(required = false)
    private I18nProvider i18nProvider;

    @Autowired(required = false)
    @Qualifier("userPreferencesBackingBean")
    private transient Object userPreferencesBean;

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
        if (language == null || "de".equalsIgnoreCase(language)) {
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

        if (targetLanguage == null || "de".equalsIgnoreCase(targetLanguage)) {
            return defaultGerman;
        }

        return i18nProvider.translate(defaultGerman, targetLanguage);
    }

    /**
     * Resolves the current user's preferred language from the session-scoped
     * UserPreferencesBackingBean via reflection (to avoid circular module dependencies).
     *
     * @return language code (e.g., "de", "en") or "de" as fallback
     */
    private String resolveUserLanguage() {
        try {
            // Try to get language from UserPreferencesBackingBean via reflection
            // (avoids compile-time dependency on plaintext-root-webapp)
            if (userPreferencesBean != null) {
                java.lang.reflect.Method getLanguage = userPreferencesBean.getClass().getMethod("getLanguage");
                Object lang = getLanguage.invoke(userPreferencesBean);
                if (lang instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve user language from UserPreferencesBackingBean: {}", e.getMessage());
        }

        // Fallback: try to get from Spring Security authentication
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                // Default to "de" for authenticated users without explicit language preference
                return "de";
            }
        } catch (Exception e) {
            log.debug("Could not resolve authentication: {}", e.getMessage());
        }

        return "de";
    }
}

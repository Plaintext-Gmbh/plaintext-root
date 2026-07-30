/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import java.util.List;

/**
 * Interface for providing i18n translation support.
 * <p>
 * This interface allows optional integration of translation capabilities.
 * If no implementation is found in the Spring context, the system will use default German labels.
 * If an implementation is present, it will be consulted to translate labels into the target language.
 * </p>
 *
 * @author plaintext.ch
 * @since 1.65.0
 */
public interface I18nProvider {

    /**
     * Translates a default label into the target language.
     *
     * @param defaultLabel the original label text (typically in German)
     * @param targetLanguage the ISO language code (e.g., "de", "en", "fr", "it")
     * @return the translated label, or the defaultLabel if no translation is available
     */
    String translate(String defaultLabel, String targetLanguage);

    /**
     * Checks if i18n translation is enabled.
     *
     * @return true if translations are active, false to use default labels
     */
    boolean isI18nEnabled();

    /**
     * Returns the list of available language codes.
     *
     * @return list of ISO language codes (e.g., ["de", "en", "fr", "it"])
     */
    List<String> getAvailableLanguages();
}

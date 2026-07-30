/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.repository;

import ch.plaintext.i18n.entity.I18nTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface I18nTranslationRepository extends JpaRepository<I18nTranslation, Long> {

    Optional<I18nTranslation> findByDefaultLabelAndLanguageCode(String defaultLabel, String languageCode);

    List<I18nTranslation> findByLanguageCode(String languageCode);

    List<I18nTranslation> findAllByOrderByDefaultLabelAscLanguageCodeAsc();

    @Query("SELECT DISTINCT t.languageCode FROM I18nTranslation t ORDER BY t.languageCode")
    List<String> findDistinctLanguageCodes();
}

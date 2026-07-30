/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity for storing i18n translations.
 * Each row maps a default label + language code to a translated text.
 */
@Entity
@Table(name = "i18n_translation",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_i18n_label_lang",
        columnNames = {"default_label", "language_code"}
    ),
    indexes = {
        @Index(name = "idx_i18n_language_code", columnList = "language_code"),
        @Index(name = "idx_i18n_default_label", columnList = "default_label")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class I18nTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "default_label", nullable = false, length = 500)
    private String defaultLabel;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    @Column(name = "translated_text", nullable = false, length = 2000)
    private String translatedText;
}

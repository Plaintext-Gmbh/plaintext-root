/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.service;

import ch.plaintext.i18n.entity.I18nTranslation;
import ch.plaintext.i18n.repository.I18nTranslationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Zustandsbericht 29.08.2026, Welle 2: Der Seed-Importer ist die einzige Stelle, an der ein Deploy
 * die Uebersetzungstabelle anfasst. Diese Tests belegen die Zusage aus dem Javadoc von
 * {@link I18nService#importSeedTranslations()}: fehlende Zeilen werden angelegt, {@code X_}-Platzhalter
 * ersetzt, ein gepflegter Text wird NIE ueberschrieben. Gelesen wird die echte Seed dieses Moduls
 * ({@code i18n/plaintext-root.csv} auf dem Test-Classpath).
 */
@DisplayName("I18nService.importSeedTranslations: Vorbelegung nur fuer fehlende Schluessel")
class I18nServiceSeedImportTest {

    private I18nTranslationRepository repository;
    private I18nService service;
    private final Map<String, I18nTranslation> db = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(I18nTranslationRepository.class);
        when(repository.findByDefaultLabelAndLanguageCode(anyString(), anyString()))
                .thenAnswer(a -> Optional.ofNullable(db.get(a.getArgument(0) + "::" + a.getArgument(1))));
        when(repository.save(any(I18nTranslation.class))).thenAnswer(a -> {
            I18nTranslation t = a.getArgument(0);
            db.put(t.getDefaultLabel() + "::" + t.getLanguageCode(), t);
            return t;
        });
        service = new I18nService(repository);
    }

    @Test
    @DisplayName("Leere Datenbank: jede Seed-Zeile wird angelegt (Speichern -> Save)")
    void leereDatenbankWirdVorbelegt() {
        service.importSeedTranslations();

        assertTrue(db.size() >= 280, "Seed plaintext-root.csv erwartet, angelegt: " + db.size());
        assertEquals("Save", db.get("Speichern::en").getTranslatedText());
        assertTrue(db.keySet().stream().allMatch(k -> k.endsWith("::en")), "nur en-Zeilen erwartet: " + db.keySet());
    }

    @Test
    @DisplayName("Gepflegter Text bleibt: 'Store' wird nicht durch die Seed 'Save' ersetzt")
    void gepflegterTextWirdNichtUeberschrieben() {
        I18nTranslation gepflegt = new I18nTranslation(7L, "Speichern", "en", "Store");
        db.put("Speichern::en", gepflegt);

        service.importSeedTranslations();

        assertEquals("Store", db.get("Speichern::en").getTranslatedText());
        ArgumentCaptor<I18nTranslation> saved = ArgumentCaptor.forClass(I18nTranslation.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertFalse(saved.getAllValues().stream().anyMatch(t -> "Speichern".equals(t.getDefaultLabel())),
                "save() fuer den gepflegten Eintrag aufgerufen");
    }

    @Test
    @DisplayName("X_-Platzhalter wird durch die Seed ersetzt — auf demselben Datensatz (Id bleibt)")
    void platzhalterWirdErsetzt() {
        db.put("Speichern::en", new I18nTranslation(7L, "Speichern", "en", "X_Speichern"));

        service.importSeedTranslations();

        I18nTranslation t = db.get("Speichern::en");
        assertEquals("Save", t.getTranslatedText());
        assertEquals(7L, t.getId());
    }

    @Test
    @DisplayName("Zweiter Lauf aendert nichts mehr (idempotent)")
    void zweiterLaufIstIdempotent() {
        service.importSeedTranslations();
        int nachErstemLauf = db.size();
        List<String> texte = db.values().stream().map(I18nTranslation::getTranslatedText).sorted().toList();

        service.importSeedTranslations();

        assertEquals(nachErstemLauf, db.size());
        assertEquals(texte, db.values().stream().map(I18nTranslation::getTranslatedText).sorted().toList());
    }
}

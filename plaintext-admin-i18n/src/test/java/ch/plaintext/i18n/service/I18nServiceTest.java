/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.service;

import ch.plaintext.i18n.entity.I18nTranslation;
import ch.plaintext.i18n.repository.I18nTranslationRepository;
import ch.plaintext.settings.ISettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Status report 29.08.2026, measure 13 (JaCoCo gate): apart from {@code isI18nEnabled()},
 * {@code I18nService} was untested — yet the cache, the creation of placeholders and the CSV seed
 * import are exactly the places where a bug silently loses or overwrites translations.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class I18nServiceTest {

    @Mock
    private I18nTranslationRepository repository;

    @Mock
    private ISettingsService settings;

    private I18nService service;

    @BeforeEach
    void setUp() {
        service = new I18nService(repository);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static I18nTranslation eintrag(String label, String lang, String text) {
        I18nTranslation t = new I18nTranslation();
        t.setDefaultLabel(label);
        t.setLanguageCode(lang);
        t.setTranslatedText(text);
        return t;
    }

    @Nested
    @DisplayName("translate()")
    class Translate {

        @Test
        void nullArgumenteLiefernDasLabelZurueck() {
            assertNull(service.translate(null, "en"));
            assertEquals("Speichern", service.translate("Speichern", null));
        }

        @Test
        void deutschIstQuellspracheUndWirdNichtNachgeschlagen() {
            assertEquals("Speichern", service.translate("Speichern", "DE"));
            verify(repository, never()).findByDefaultLabelAndLanguageCode(anyString(), anyString());
        }

        @Test
        void trefferAusDerDatenbankWirdGecacht() {
            when(repository.findByDefaultLabelAndLanguageCode("Speichern", "en"))
                    .thenReturn(Optional.of(eintrag("Speichern", "en", "Save")));

            assertEquals("Save", service.translate("Speichern", "en"));
            assertEquals("Save", service.translate("Speichern", "en"));

            verify(repository, org.mockito.Mockito.times(1)).findByDefaultLabelAndLanguageCode("Speichern", "en");
        }

        @Test
        void ohneUebersetzungEntstehtEinPlatzhalterMitPraefix() {
            when(repository.findByDefaultLabelAndLanguageCode("Neu", "fr")).thenReturn(Optional.empty());

            assertEquals("X_Neu", service.translate("Neu", "fr"));

            ArgumentCaptor<I18nTranslation> captor = ArgumentCaptor.forClass(I18nTranslation.class);
            verify(repository).save(captor.capture());
            assertEquals("Neu", captor.getValue().getDefaultLabel());
            assertEquals("fr", captor.getValue().getLanguageCode());
            assertEquals("X_Neu", captor.getValue().getTranslatedText());
            assertTrue(service.isPlaceholder(captor.getValue().getTranslatedText()));
        }

        @Test
        void fehlerBeimAnlegenDesPlatzhaltersBrichtDieUebersetzungNichtAb() {
            when(repository.findByDefaultLabelAndLanguageCode("Neu", "it")).thenReturn(Optional.empty());
            when(repository.save(any())).thenThrow(new IllegalStateException("DB weg"));

            assertEquals("X_Neu", service.translate("Neu", "it"));
        }
    }

    @Test
    void autoCreatePlaceholderLegtNichtsDoppeltAn() {
        when(repository.findByDefaultLabelAndLanguageCode("Neu", "en"))
                .thenReturn(Optional.of(eintrag("Neu", "en", "New")));

        service.autoCreatePlaceholder("Neu", "en", "X_Neu");

        verify(repository, never()).save(any());
    }

    @Test
    void saveTranslationAktualisiertBestandUndCache() {
        I18nTranslation bestehend = eintrag("Speichern", "en", "X_Speichern");
        when(repository.findByDefaultLabelAndLanguageCode("Speichern", "en")).thenReturn(Optional.of(bestehend));

        I18nTranslation gespeichert = service.saveTranslation("Speichern", "en", "Save");

        assertSame(bestehend, gespeichert);
        assertEquals("Save", bestehend.getTranslatedText());
        // The cache takes effect: the DB is no longer queried for the lookup.
        assertEquals("Save", service.translate("Speichern", "en"));
        verify(repository, org.mockito.Mockito.times(1)).findByDefaultLabelAndLanguageCode("Speichern", "en");
    }

    @Test
    void deleteTranslationEntferntEintragUndCache() {
        I18nTranslation t = eintrag("Speichern", "en", "Save");
        when(repository.findById(7L)).thenReturn(Optional.of(t));
        service.saveTranslation("Speichern", "en", "Save");

        service.deleteTranslation(7L);

        verify(repository).delete(t);
        // After the deletion translate() falls back to the DB again.
        when(repository.findByDefaultLabelAndLanguageCode("Speichern", "en")).thenReturn(Optional.empty());
        assertEquals("X_Speichern", service.translate("Speichern", "en"));
    }

    @Test
    void deleteTranslationOhneTrefferTutNichts() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        service.deleteTranslation(99L);
        verify(repository, never()).delete(any());
    }

    @Test
    void listenDelegierenAnsRepository() {
        List<I18nTranslation> alle = List.of(eintrag("A", "en", "a"));
        when(repository.findAllByOrderByDefaultLabelAscLanguageCodeAsc()).thenReturn(alle);
        when(repository.findByLanguageCode("en")).thenReturn(alle);

        assertSame(alle, service.getAllTranslations());
        assertSame(alle, service.getTranslationsByLanguage("en"));
    }

    @Test
    void clearCacheLaedtDenCacheAusDerDatenbankNeu() {
        when(repository.findAll()).thenReturn(List.of(eintrag("Speichern", "en", "Save")));

        service.clearCache();

        assertEquals("Save", service.translate("Speichern", "en"));
        verify(repository, never()).findByDefaultLabelAndLanguageCode(anyString(), anyString());
    }

    @Test
    void isPlaceholderErkenntNurDasPraefix() {
        assertTrue(service.isPlaceholder("X_Foo"));
        assertFalse(service.isPlaceholder("Foo"));
        assertFalse(service.isPlaceholder(null));
    }

    @Nested
    @DisplayName("getAvailableLanguages()")
    class Sprachen {

        @Test
        void ohneSettingsServiceDieVierStandardsprachen() {
            assertEquals(List.of("de", "en", "fr", "it"), service.getAvailableLanguages());
        }

        @Test
        void konfigurierteListeGewinnt() {
            ReflectionTestUtils.setField(service, "settingsService", settings);
            when(settings.getList("i18n.languages")).thenReturn(List.of("de", "en"));

            assertEquals(List.of("de", "en"), service.getAvailableLanguages());
        }

        @Test
        void leereOderKaputteEinstellungFaelltAufStandardZurueck() {
            ReflectionTestUtils.setField(service, "settingsService", settings);
            when(settings.getList("i18n.languages")).thenReturn(List.of());
            assertEquals(4, service.getAvailableLanguages().size());

            when(settings.getList("i18n.languages")).thenThrow(new IllegalStateException("kaputt"));
            assertEquals(4, service.getAvailableLanguages().size());
        }
    }

    @Nested
    @DisplayName("importSeedTranslations() — src/test/resources/i18n/seed-test.csv")
    class SeedImport {

        @Test
        void importiertNeueUeberschreibtPlatzhalterUndLaesstEchteUebersetzungenStehen() {
            when(repository.findByDefaultLabelAndLanguageCode("Vorhanden", "en"))
                    .thenReturn(Optional.of(eintrag("Vorhanden", "en", "Bestehend")));
            I18nTranslation platzhalter = eintrag("Platzhalter", "en", "X_Platzhalter");
            when(repository.findByDefaultLabelAndLanguageCode("Platzhalter", "en"))
                    .thenReturn(Optional.of(platzhalter));

            service.importSeedTranslations();

            ArgumentCaptor<I18nTranslation> captor = ArgumentCaptor.forClass(I18nTranslation.class);
            verify(repository, atLeastOnce()).save(captor.capture());
            // Key is label + language, not just label: on the test classpath, next to the fixture,
            // the real seed plaintext-root.csv is present too, and it carries every label in three
            // languages ("Speichern" -> Save/Enregistrer/Salva).
            Map<String, String> gespeichert = captor.getAllValues().stream()
                    .collect(Collectors.toMap(t -> t.getDefaultLabel() + "::" + t.getLanguageCode(),
                            I18nTranslation::getTranslatedText, (a, b) -> b));

            assertEquals("Save", gespeichert.get("Speichern::en"));
            assertEquals("Cancel", gespeichert.get("Abbrechen::en"));
            assertEquals("With \"quotes\"", gespeichert.get("Zitat::en"), "Anfuehrungszeichen werden entschaerft");
            assertEquals("Real", gespeichert.get("Platzhalter::en"), "X_-Platzhalter wird ueberschrieben");
            assertSame(platzhalter, captor.getAllValues().stream()
                    .filter(t -> "Platzhalter".equals(t.getDefaultLabel())).findFirst().orElseThrow(),
                    "bestehender Datensatz wird aktualisiert, nicht dupliziert");
            assertFalse(gespeichert.containsKey("Vorhanden::en"), "echte Uebersetzung bleibt unangetastet");
            assertFalse(gespeichert.containsKey("Kaputt::en"), "Zeile mit zwei Spalten wird uebersprungen");
            assertFalse(gespeichert.containsKey("::en"), "Zeile ohne Label wird uebersprungen");
        }

        @Test
        void initFuehrtImportUndCacheLadenAus() {
            when(repository.findAll()).thenReturn(List.of(eintrag("Speichern", "en", "Save")));

            service.init();

            verify(repository).findAll();
            assertEquals("Save", service.translate("Speichern", "en"));
        }
    }
}

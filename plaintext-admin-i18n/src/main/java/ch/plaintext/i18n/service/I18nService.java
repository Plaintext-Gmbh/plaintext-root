/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.service;

import ch.plaintext.I18nProvider;
import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import ch.plaintext.boot.utils.I18nSeedLinter;
import ch.plaintext.i18n.entity.I18nTranslation;
import ch.plaintext.i18n.repository.I18nTranslationRepository;
import ch.plaintext.settings.ISettingsService;
import ch.plaintext.settings.SettingsKeys;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * I18n translation service implementing the I18nProvider interface.
 * Provides caching, DB lookup, and fallback to the default label.
 */
@Service
@Slf4j
public class I18nService implements I18nProvider {

    private final I18nTranslationRepository repository;

    @Autowired(required = false)
    private ISettingsService settingsService;

    /**
     * Cache: key = "defaultLabel::languageCode", value = translated text.
     */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public I18nService(I18nTranslationRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        importSeedTranslations();
        loadCache();
    }

    private void loadCache() {
        cache.clear();
        List<I18nTranslation> all = repository.findAll();
        for (I18nTranslation t : all) {
            cache.put(cacheKey(t.getDefaultLabel(), t.getLanguageCode()), t.getTranslatedText());
        }
        log.info("I18n cache loaded with {} translations", all.size());
    }

    /** Suchmuster des Seed-Importers — dieselben Dateien prueft {@code PlaintextI18nSeedTest} (plaintext-root-archtests). */
    public static final String SEED_PATTERN = "classpath*:i18n/*.csv";

    /**
     * Vorbelegung aus den Seed-Dateien ({@value #SEED_PATTERN}) — beim Start, vor dem Laden des
     * Caches. Format und Lese-Regeln: {@link I18nSeedLinter}.
     *
     * <p><b>Idempotent und nie destruktiv.</b> Eine Zeile wird nur geschrieben, wenn zu
     * (Label, Sprache) noch kein Eintrag existiert oder der vorhandene ein automatisch erzeugter
     * {@code X_}-Platzhalter ist ({@link #isPlaceholder}). Ein Text, den jemand unter
     * Root → Uebersetzungen oder per {@code /api/i18n/import} gepflegt hat, bleibt bei jedem
     * Deploy stehen — die Seed-Datei ist eine Vorbelegung fuer fehlende Schluessel, keine Quelle
     * der Wahrheit. Wer einen gepflegten Text zuruecksetzen will, loescht den Eintrag oder setzt
     * ihn auf einen {@code X_}-Platzhalter; der naechste Start fuellt ihn aus der Seed neu.
     *
     * <p>Zustandsbericht 29.08.2026, Welle 2: Bis dahin lief der Importer bei jedem Start leer,
     * weil familienweit keine Seed-Datei existierte; seither liegt {@code i18n/plaintext-root.csv}
     * in diesem Modul, und jeder Consumer kann eigene Dateien unter {@code src/main/resources/i18n/}
     * beilegen. Das Zeilen-Parsing teilt sich der Importer mit dem Test — vorher stand es hier als
     * private Kopie.
     */
    @Transactional
    public void importSeedTranslations() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(SEED_PATTERN);

            if (resources.length == 0) {
                log.debug("No i18n seed CSV files found on classpath");
                return;
            }

            int totalImported = 0;
            int totalSkipped = 0;

            for (Resource resource : resources) {
                if (!resource.isReadable()) {
                    continue;
                }

                String filename = resource.getFilename();
                log.info("Importing i18n seed file: {}", filename);

                I18nSeedLinter.Seed seed;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    seed = I18nSeedLinter.parse(reader);
                }
                seed.problems().forEach(p -> log.warn("Seed CSV {}: {}", filename, p));

                int imported = 0;
                int skipped = seed.problems().size();
                for (I18nSeedLinter.SeedRow row : seed.rows()) {
                    if (importSeedRow(row)) {
                        imported++;
                    } else {
                        skipped++;
                    }
                }

                log.info("Seed CSV {}: {} imported, {} skipped", filename, imported, skipped);
                totalImported += imported;
                totalSkipped += skipped;
            }

            if (totalImported > 0) {
                log.info("I18n seed import completed: {} imported, {} skipped from {} files",
                        totalImported, totalSkipped, resources.length);
            }

        } catch (Exception e) {
            log.warn("Error importing i18n seed translations: {}", e.getMessage());
        }
    }

    /**
     * Schreibt eine Seed-Zeile, wenn zu (Label, Sprache) nichts oder nur ein {@code X_}-Platzhalter
     * vorliegt.
     *
     * @return true = angelegt/ersetzt, false = gepflegter Eintrag vorhanden, nicht angefasst
     */
    private boolean importSeedRow(I18nSeedLinter.SeedRow row) {
        Optional<I18nTranslation> existing = repository.findByDefaultLabelAndLanguageCode(row.defaultLabel(), row.languageCode());
        if (existing.isPresent() && !isPlaceholder(existing.get().getTranslatedText())) {
            return false;
        }
        I18nTranslation translation = existing.orElse(new I18nTranslation());
        translation.setDefaultLabel(row.defaultLabel());
        translation.setLanguageCode(row.languageCode());
        translation.setTranslatedText(row.translatedText());
        repository.save(translation);
        return true;
    }

    /** Prefix used for auto-generated placeholder translations. */
    public static final String UNTRANSLATED_PREFIX = "X_";

    @Override
    public String translate(String defaultLabel, String targetLanguage) {
        if (defaultLabel == null || targetLanguage == null) {
            return defaultLabel;
        }

        // "de" is the source language - no translation needed
        if ("de".equalsIgnoreCase(targetLanguage)) {
            return defaultLabel;
        }

        // Lookup in cache first
        String key = cacheKey(defaultLabel, targetLanguage);
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        // Fallback: lookup in DB
        Optional<I18nTranslation> found = repository.findByDefaultLabelAndLanguageCode(defaultLabel, targetLanguage);
        if (found.isPresent()) {
            String translated = found.get().getTranslatedText();
            cache.put(key, translated);
            return translated;
        }

        // No translation found: auto-create a placeholder with "X_" prefix
        String placeholder = UNTRANSLATED_PREFIX + defaultLabel;
        try {
            autoCreatePlaceholder(defaultLabel, targetLanguage, placeholder);
        } catch (Exception e) {
            log.warn("Could not auto-create i18n placeholder for label='{}', lang='{}': {}",
                    defaultLabel, targetLanguage, e.getMessage());
        }

        cache.put(key, placeholder);
        return placeholder;
    }

    /**
     * Auto-inserts a placeholder translation record so untranslated items
     * become visible in the admin UI (prefixed with "X_").
     */
    @Transactional
    public void autoCreatePlaceholder(String defaultLabel, String languageCode, String placeholder) {
        // Double-check to avoid unique constraint violations in concurrent scenarios
        Optional<I18nTranslation> existing = repository.findByDefaultLabelAndLanguageCode(defaultLabel, languageCode);
        if (existing.isPresent()) {
            return;
        }
        I18nTranslation translation = new I18nTranslation();
        translation.setDefaultLabel(defaultLabel);
        translation.setLanguageCode(languageCode);
        translation.setTranslatedText(placeholder);
        repository.save(translation);
        log.info("Auto-created i18n placeholder: label='{}', lang='{}', text='{}'", defaultLabel, languageCode, placeholder);
    }

    /**
     * Ob i18n (Sprachwechsel in der Topbar, uebersetzte Menuetitel, {@code i18n.t()}) fuer den
     * Mandanten der Session an ist.
     *
     * <p>Auftrag Daniel, 29.08.2026: Das Setup speichert den Schalter als
     * {@code branding.i18n.enabled} je Mandant (BrandingService), diese Methode las aber nur den
     * alten Schluessel {@code i18n.enabled} — zwei Schluessel, die sich nie sahen (seit R2 des
     * Zustandsberichts stehen beide einmal in {@link SettingsKeys}). Ergebnis: Der
     * Sprachwechsel blieb in der Topbar, obwohl er im Setup ausgeschaltet war. Reihenfolge jetzt:
     * Mandanten-Schalter aus dem Setup, dann der alte Schluessel, sonst an.</p>
     */
    @Override
    public boolean isI18nEnabled() {
        if (settingsService == null) {
            return true;
        }
        try {
            String mandat = PlaintextSecurityHolder.getMandat();
            if (mandat != null && !mandat.isBlank()) {
                Boolean setup = settingsService.getBoolean(SettingsKeys.I18N_ENABLED, mandat);
                if (setup != null) {
                    return setup;
                }
            }
            Boolean enabled = settingsService.getBoolean(SettingsKeys.I18N_ENABLED_LEGACY);
            if (enabled != null) {
                return enabled;
            }
        } catch (Exception e) {
            log.debug("Could not read i18n setting, defaulting to true: {}", e.toString());
        }
        return true;
    }

    @Override
    public List<String> getAvailableLanguages() {
        if (settingsService != null) {
            try {
                List<String> langs = settingsService.getList("i18n.languages");
                if (langs != null && !langs.isEmpty()) {
                    return langs;
                }
            } catch (Exception e) {
                log.debug("Could not read i18n.languages setting, using defaults");
            }
        }
        return Arrays.asList("de", "en", "fr", "it");
    }

    @Transactional
    public I18nTranslation saveTranslation(String defaultLabel, String languageCode, String translatedText) {
        I18nTranslation translation = repository
                .findByDefaultLabelAndLanguageCode(defaultLabel, languageCode)
                .orElse(new I18nTranslation());

        translation.setDefaultLabel(defaultLabel);
        translation.setLanguageCode(languageCode);
        translation.setTranslatedText(translatedText);

        I18nTranslation saved = repository.save(translation);
        cache.put(cacheKey(defaultLabel, languageCode), translatedText);
        log.info("Saved i18n translation: label='{}', lang='{}', text='{}'", defaultLabel, languageCode, translatedText);
        return saved;
    }

    @Transactional
    public void deleteTranslation(Long id) {
        repository.findById(id).ifPresent(t -> {
            cache.remove(cacheKey(t.getDefaultLabel(), t.getLanguageCode()));
            repository.delete(t);
            log.info("Deleted i18n translation: id={}, label='{}', lang='{}'", id, t.getDefaultLabel(), t.getLanguageCode());
        });
    }

    public List<I18nTranslation> getAllTranslations() {
        return repository.findAllByOrderByDefaultLabelAscLanguageCodeAsc();
    }

    public List<I18nTranslation> getTranslationsByLanguage(String languageCode) {
        return repository.findByLanguageCode(languageCode);
    }

    public void clearCache() {
        cache.clear();
        loadCache();
        log.info("I18n cache cleared and reloaded");
    }

    /**
     * Returns true if the given translated text is still an auto-generated placeholder.
     */
    public boolean isPlaceholder(String translatedText) {
        return translatedText != null && translatedText.startsWith(UNTRANSLATED_PREFIX);
    }

    /**
     * Returns all translations that are still placeholders (starting with "X_").
     */
    public List<I18nTranslation> getUntranslatedEntries() {
        return getAllTranslations().stream()
                .filter(t -> isPlaceholder(t.getTranslatedText()))
                .toList();
    }

    /**
     * Returns all translations for a given language that are still placeholders.
     */
    public List<I18nTranslation> getUntranslatedEntries(String languageCode) {
        return getTranslationsByLanguage(languageCode).stream()
                .filter(t -> isPlaceholder(t.getTranslatedText()))
                .toList();
    }

    private String cacheKey(String defaultLabel, String languageCode) {
        return defaultLabel + "::" + languageCode;
    }
}

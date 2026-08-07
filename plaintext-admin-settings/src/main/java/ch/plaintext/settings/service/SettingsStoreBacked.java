/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.service;

import ch.plaintext.settings.entity.Setting;
import ch.plaintext.settings.repository.SettingRepository;
import ch.plaintext.store.StoreBacked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Makes the {@code SETTING} table backed up and restorable through any
 * {@link ch.plaintext.store.TableStore}.
 *
 * <p>Settings exist nowhere else: they are entered by hand, not derived from an external
 * system, so a lost database means lost configuration. This class supplies the rows and the
 * merge semantics; where they are stored is decided by the application that provides the
 * {@code TableStore} bean.</p>
 *
 * <p><b>Restore semantics: the database wins.</b> Only keys missing from the database are
 * created from the store. A restore triggered at runtime must not overwrite settings that
 * were changed since the backup. On a fresh boot the table is empty, so everything comes
 * back from the store — which is the case that matters.</p>
 *
 * <p>The row format matches what the previous, application-specific exporter wrote, so
 * existing backups remain readable without migration.</p>
 */
@Service
public class SettingsStoreBacked implements StoreBacked {

    private static final Logger log = LoggerFactory.getLogger(SettingsStoreBacked.class);

    /** Logical name of the collection — a wiki backend uses it as the child page title. */
    public static final String STORE_ID = "Settings";

    private final SettingRepository settingRepository;

    public SettingsStoreBacked(SettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @Override
    public String storeId() {
        return STORE_ID;
    }

    @Override
    public int storeOrder() {
        // Early: other collections may read settings while being restored themselves.
        return 2;
    }

    @Override
    public List<Map<String, String>> exportRows() {
        return settingRepository.findAll().stream()
                .filter(s -> s.getKey() != null && !s.getKey().isBlank())
                .sorted(Comparator.comparing(Setting::getKey, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Setting::getMandat, Comparator.nullsLast(String::compareTo)))
                .map(SettingsStoreBacked::entityToRow)
                .toList();
    }

    @Override
    public long importRows(List<Map<String, String>> rows) {
        long merged = 0;
        long skipped = 0;
        for (Map<String, String> row : rows) {
            String key = row.getOrDefault("key", "").trim();
            if (key.isEmpty()) {
                continue;
            }
            String mandat = row.getOrDefault("mandat", "default").trim();
            if (settingRepository.findByKeyAndMandat(key, mandat).isPresent()) {
                skipped++;
                continue;
            }
            settingRepository.save(rowToEntity(row));
            merged++;
        }
        log.info("[SettingsStoreBacked] import | merged={} | skipped={}", merged, skipped);
        return merged;
    }

    @Override
    public long entityCount() {
        return settingRepository.count();
    }

    /**
     * Sorting is deliberate: it keeps the exported rows stable across runs so that the
     * content hash only changes when a setting actually changed, not when the database
     * returns a different order.
     */
    static Map<String, String> entityToRow(Setting setting) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("key", setting.getKey());
        row.put("mandat", setting.getMandat());
        row.put("value", Objects.toString(setting.getValue(), ""));
        row.put("valueType", Objects.toString(setting.getValueType(), "STRING"));
        row.put("description", Objects.toString(setting.getDescription(), ""));
        return row;
    }

    static Setting rowToEntity(Map<String, String> row) {
        Setting setting = new Setting();
        setting.setKey(row.getOrDefault("key", "").trim());
        setting.setMandat(row.getOrDefault("mandat", "default").trim());
        setting.setValue(row.getOrDefault("value", ""));
        String valueType = row.getOrDefault("valueType", "STRING");
        setting.setValueType(valueType.isBlank() ? "STRING" : valueType);
        setting.setDescription(row.getOrDefault("description", ""));
        return setting;
    }
}

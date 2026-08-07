/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.service;

import ch.plaintext.settings.entity.Setting;
import ch.plaintext.settings.repository.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettingsStoreBackedTest {

    @Mock
    private SettingRepository settingRepository;

    private SettingsStoreBacked store;

    @BeforeEach
    void setUp() {
        store = new SettingsStoreBacked(settingRepository);
    }

    private Setting setting(String key, String value) {
        Setting s = new Setting();
        s.setKey(key);
        s.setMandat("default");
        s.setValue(value);
        s.setValueType("STRING");
        s.setDescription("");
        return s;
    }

    /**
     * The columns must stay exactly as the previous exporter wrote them — otherwise the
     * backups already sitting in the store become unreadable.
     */
    @Test
    void rowFormatStaysCompatible() {
        Map<String, String> row = SettingsStoreBacked.entityToRow(setting("app.title", "Inventar"));

        assertEquals(List.of("key", "mandat", "value", "valueType", "description"),
                List.copyOf(row.keySet()));
        assertEquals("app.title", row.get("key"));
        assertEquals("default", row.get("mandat"));
        assertEquals("Inventar", row.get("value"));
        assertEquals("STRING", row.get("valueType"));
    }

    @Test
    void roundTripKeepsValues() {
        Setting original = setting("app.title", "Inventar");

        Setting restored = SettingsStoreBacked.rowToEntity(SettingsStoreBacked.entityToRow(original));

        assertEquals(original.getKey(), restored.getKey());
        assertEquals(original.getMandat(), restored.getMandat());
        assertEquals(original.getValue(), restored.getValue());
        assertEquals(original.getValueType(), restored.getValueType());
    }

    @Test
    void nullValuesBecomeEmptyStringsNotNullText() {
        Setting s = new Setting();
        s.setKey("k");
        s.setMandat("default");

        Map<String, String> row = SettingsStoreBacked.entityToRow(s);

        assertEquals("", row.get("value"));
        assertEquals("STRING", row.get("valueType"), "fehlender Typ faellt auf STRING zurueck");
    }

    /** Missing valueType in a hand-edited row must not produce a blank type. */
    @Test
    void blankValueTypeFallsBackToString() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("key", "k");
        row.put("valueType", "   ");

        assertEquals("STRING", SettingsStoreBacked.rowToEntity(row).getValueType());
    }

    // ── Restore semantics: the database wins ───────────────────────────────

    @Test
    void existingKeyIsNotOverwritten() {
        when(settingRepository.findByKeyAndMandat("app.title", "default"))
                .thenReturn(Optional.of(setting("app.title", "schon da")));

        long merged = store.importRows(List.of(SettingsStoreBacked.entityToRow(setting("app.title", "aus dem Store"))));

        assertEquals(0, merged);
        verify(settingRepository, never()).save(any());
    }

    @Test
    void missingKeyIsCreated() {
        when(settingRepository.findByKeyAndMandat(anyString(), anyString())).thenReturn(Optional.empty());

        long merged = store.importRows(List.of(SettingsStoreBacked.entityToRow(setting("app.title", "neu"))));

        assertEquals(1, merged);
        verify(settingRepository, times(1)).save(any());
    }

    @Test
    void rowsWithoutKeyAreIgnored() {
        Map<String, String> broken = new LinkedHashMap<>();
        broken.put("key", "  ");
        broken.put("value", "irgendwas");

        assertEquals(0, store.importRows(List.of(broken)));
        verify(settingRepository, never()).save(any());
    }

    // ── Export ─────────────────────────────────────────────────────────────

    /**
     * A stable order matters: the guard layer compares content hashes, and an unstable
     * row order would look like a change on every run.
     */
    @Test
    void exportIsSortedForStableHashing() {
        when(settingRepository.findAll()).thenReturn(List.of(
                setting("z.last", "1"), setting("a.first", "2"), setting("m.middle", "3")));

        List<Map<String, String>> rows = store.exportRows();

        assertEquals(List.of("a.first", "m.middle", "z.last"),
                rows.stream().map(r -> r.get("key")).toList());
    }

    @Test
    void exportSkipsEntriesWithoutKey() {
        Setting broken = new Setting();
        broken.setMandat("default");

        when(settingRepository.findAll()).thenReturn(List.of(setting("ok", "1"), broken));

        assertEquals(1, store.exportRows().size());
    }

    @Test
    void metadataIsAsExpected() {
        assertEquals("Settings", store.storeId());
        assertTrue(store.restoreOnStartup());
        assertEquals(2, store.storeOrder(), "frueh, andere Sammlungen lesen Settings");
    }
}

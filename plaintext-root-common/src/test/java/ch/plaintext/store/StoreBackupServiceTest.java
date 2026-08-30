/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guard layer — every test pins down a failure that really occurred
 * or that the previous 14-fold individual wiring could have caused.
 */
class StoreBackupServiceTest {

    private RecordingStore store;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
    }

    // ── Empty guard ────────────────────────────────────────────────────────

    /**
     * Prod 07.08.2026: after the aborted ingest the outgoing push wrote the empty
     * application list to Confluence and thereby overwrote the source.
     */
    @Test
    void emptyCollectionIsNeverWritten() {
        TestCollection empty = new TestCollection("Leer", List.of());
        StoreBackupService service = new StoreBackupService(store, List.of(empty));
        service.restore(empty);

        assertFalse(service.backup(empty, false));
        assertFalse(service.backup(empty, true), "auch force darf die Ablage nicht leeren");
        assertEquals(0, store.saveCount);
    }

    // ── Restore gate ───────────────────────────────────────────────────────

    @Test
    void backupIsBlockedBeforeRestore() {
        TestCollection c = new TestCollection("Daten", rows("a"));
        StoreBackupService service = new StoreBackupService(store, List.of(c));

        assertFalse(service.backup(c, false));
        assertFalse(service.backup(c, true));
        assertEquals(0, store.saveCount);
    }

    @Test
    void backupWorksAfterSuccessfulRestore() {
        TestCollection c = new TestCollection("Daten", rows("a"));
        StoreBackupService service = new StoreBackupService(store, List.of(c));

        service.restore(c);

        assertTrue(service.backup(c, false));
        assertEquals(1, store.saveCount);
    }

    /** If the restore fails, the gate stays shut — otherwise a partial state writes the store. */
    @Test
    void failedRestoreKeepsGateClosed() {
        TestCollection c = new TestCollection("Kaputt", rows("a"));
        c.failImport = true;
        StoreBackupService service = new StoreBackupService(store, List.of(c));

        assertFalse(service.restore(c));
        assertFalse(service.isRestoreCompleted("Kaputt"));
        assertFalse(service.backup(c, false));
        assertEquals(0, store.saveCount);
    }

    // ── Content hash ───────────────────────────────────────────────────────

    /** MyUser stood at over 12'000 revisions, nearly all with identical content. */
    @Test
    void unchangedContentIsWrittenOnlyOnce() {
        TestCollection c = new TestCollection("Daten", rows("a", "b"));
        StoreBackupService service = new StoreBackupService(store, List.of(c));
        service.restore(c);

        assertTrue(service.backup(c, false));
        assertFalse(service.backup(c, false));
        assertFalse(service.backup(c, false));

        assertEquals(1, store.saveCount);
    }

    @Test
    void changedContentIsWrittenAgain() {
        TestCollection c = new TestCollection("Daten", rows("a"));
        StoreBackupService service = new StoreBackupService(store, List.of(c));
        service.restore(c);

        service.backup(c, false);
        c.current = rows("a", "b");
        assertTrue(service.backup(c, false));

        assertEquals(2, store.saveCount);
    }

    @Test
    void forceBypassesContentHash() {
        TestCollection c = new TestCollection("Daten", rows("a"));
        StoreBackupService service = new StoreBackupService(store, List.of(c));
        service.restore(c);

        service.backup(c, false);
        assertTrue(service.backup(c, true));

        assertEquals(2, store.saveCount);
    }

    // ── Ordering and encapsulation ─────────────────────────────────────────

    @Test
    void restoreFollowsStoreOrder() {
        TestCollection spaet = new TestCollection("Spaet", rows("a"));
        spaet.order = 200;
        TestCollection frueh = new TestCollection("Frueh", rows("a"));
        frueh.order = 10;

        StoreBackupService service = new StoreBackupService(store, List.of(spaet, frueh));
        service.restoreAll();

        assertEquals(List.of("Frueh", "Spaet"), store.loadOrder);
    }

    /** A broken collection must not prevent the others. */
    @Test
    void brokenCollectionDoesNotStopTheOthers() {
        TestCollection kaputt = new TestCollection("Kaputt", rows("a"));
        kaputt.failImport = true;
        TestCollection gesund = new TestCollection("Gesund", rows("a"));

        StoreBackupService service = new StoreBackupService(store, List.of(kaputt, gesund));
        service.restoreAll();

        assertFalse(service.isRestoreCompleted("Kaputt"));
        assertTrue(service.isRestoreCompleted("Gesund"));
    }

    @Test
    void restoreOnStartupFalseIsSkipped() {
        TestCollection c = new TestCollection("Manuell", rows("a"));
        c.onStartup = false;

        StoreBackupService service = new StoreBackupService(store, List.of(c));
        service.restoreAll();

        assertTrue(store.loadOrder.isEmpty());
        assertFalse(service.isRestoreCompleted("Manuell"));
    }

    /** A store that throws while saving must not drag the caller down with it. */
    @Test
    void throwingStoreIsContained() {
        store.failSave = true;
        TestCollection c = new TestCollection("Daten", rows("a"));
        StoreBackupService service = new StoreBackupService(store, List.of(c));
        service.restore(c);

        assertDoesNotThrow(() -> assertFalse(service.backup(c, false)));
    }

    /** After a failed write attempt the hash must not count as backed up. */
    @Test
    void failedSaveDoesNotMarkContentAsStored() {
        store.failSave = true;
        TestCollection c = new TestCollection("Daten", rows("a"));
        StoreBackupService service = new StoreBackupService(store, List.of(c));
        service.restore(c);

        service.backup(c, false);
        store.failSave = false;

        assertTrue(service.backup(c, false), "der zweite Versuch muss erneut schreiben");
        assertEquals(1, store.saveCount);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static List<Map<String, String>> rows(String... keys) {
        List<Map<String, String>> result = new ArrayList<>();
        for (String key : keys) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("key", key);
            result.add(row);
        }
        return result;
    }

    private static final class TestCollection implements StoreBacked {
        private final String id;
        private List<Map<String, String>> current;
        private int order = 100;
        private boolean onStartup = true;
        private boolean failImport = false;
        private final AtomicInteger imported = new AtomicInteger();

        TestCollection(String id, List<Map<String, String>> current) {
            this.id = id;
            this.current = current;
        }

        @Override public String storeId() { return id; }
        @Override public int storeOrder() { return order; }
        @Override public boolean restoreOnStartup() { return onStartup; }
        @Override public List<Map<String, String>> exportRows() { return current; }

        @Override
        public long importRows(List<Map<String, String>> rows) {
            if (failImport) {
                throw new IllegalStateException("import kaputt");
            }
            imported.set(rows.size());
            return rows.size();
        }

        @Override public long entityCount() { return current.size(); }
    }

    private static final class RecordingStore implements TableStore {
        int saveCount = 0;
        boolean failSave = false;
        final List<String> loadOrder = new ArrayList<>();

        @Override
        public List<Map<String, String>> load(String storeId) {
            loadOrder.add(storeId);
            return new ArrayList<>();
        }

        @Override
        public void save(String storeId, List<Map<String, String>> rows) {
            if (failSave) {
                throw new IllegalStateException("save kaputt");
            }
            saveCount++;
        }

        @Override
        public String backendName() {
            return "recording";
        }
    }
}

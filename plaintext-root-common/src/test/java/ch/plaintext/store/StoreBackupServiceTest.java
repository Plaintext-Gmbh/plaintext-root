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
 * Die Schutzschicht — jeder Test haelt einen Fehler fest, der real eingetreten ist
 * oder durch die bisherige 14-fache Einzelverdrahtung eintreten konnte.
 */
class StoreBackupServiceTest {

    private RecordingStore store;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
    }

    // ── Empty-Guard ────────────────────────────────────────────────────────

    /**
     * Prod 07.08.2026: Nach dem abgebrochenen Ingest schrieb der Outgoing-Push die leere
     * Applikationsliste nach Confluence und ueberschrieb damit die Quelle.
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

    // ── Restore-Gate ───────────────────────────────────────────────────────

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

    /** Scheitert der Restore, bleibt das Gate zu — sonst schreibt ein Teilzustand die Ablage. */
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

    // ── Content-Hash ───────────────────────────────────────────────────────

    /** MyUser stand bei ueber 12'000 Fassungen, fast alle inhaltsgleich. */
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

    // ── Reihenfolge und Kapselung ──────────────────────────────────────────

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

    /** Eine defekte Sammlung darf die uebrigen nicht verhindern. */
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

    /** Ein Store, der beim Speichern wirft, darf den Aufrufer nicht mitreissen. */
    @Test
    void throwingStoreIsContained() {
        store.failSave = true;
        TestCollection c = new TestCollection("Daten", rows("a"));
        StoreBackupService service = new StoreBackupService(store, List.of(c));
        service.restore(c);

        assertDoesNotThrow(() -> assertFalse(service.backup(c, false)));
    }

    /** Nach einem gescheiterten Schreibversuch darf der Hash nicht als gesichert gelten. */
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

    // ── Hilfen ─────────────────────────────────────────────────────────────

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

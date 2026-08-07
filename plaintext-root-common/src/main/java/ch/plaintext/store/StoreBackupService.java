/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Backs up and restores {@link StoreBacked} collections through a {@link TableStore}.
 *
 * <p>The value of this class is its guard layer. Every consumer that persists a collection
 * to an external medium needs the same four safeguards, and rebuilding them per consumer
 * means some will be missing:</p>
 *
 * <ul>
 *   <li><b>Restore gate</b> — never back up before the collection has been restored once.
 *       After a restart the database may be empty; without the gate the first backup run
 *       overwrites the store with nothing.</li>
 *   <li><b>Empty guard</b> — zero rows are never written over existing content. A failed
 *       import leaves the database empty, and that state must not be mistaken for the truth.</li>
 *   <li><b>Content hash</b> — unchanged content produces no new revision. Without it a
 *       periodic backup rewrites the store on every run, inflating its history until it is
 *       useless as a record of change.</li>
 *   <li><b>Per-collection lock</b> — two concurrent runs of the same collection cannot
 *       write over each other.</li>
 * </ul>
 *
 * <p>Each collection is isolated: one that fails must not stop the others.</p>
 *
 * <p>The class carries no Spring annotations — wiring (which {@link TableStore}, which
 * {@link StoreBacked} beans) belongs to the application's configuration.</p>
 */
public class StoreBackupService {

    private static final Logger log = LoggerFactory.getLogger(StoreBackupService.class);

    private final TableStore store;
    private final List<StoreBacked> collections;

    /** Collections whose restore succeeded in this process. */
    private final Map<String, Boolean> restoreCompleted = new ConcurrentHashMap<>();

    /** Content of the last successful backup per collection. */
    private final Map<String, Integer> lastSavedHash = new ConcurrentHashMap<>();

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public StoreBackupService(TableStore store, List<StoreBacked> collections) {
        this.store = store;
        this.collections = new ArrayList<>(collections);
        this.collections.sort(Comparator.comparingInt(StoreBacked::storeOrder));
    }

    /**
     * Restores every collection that opts into startup restore, in {@code storeOrder}.
     * A failing collection is logged and skipped; the remaining ones still run.
     */
    public void restoreAll() {
        int restored = 0;
        int failed = 0;
        for (StoreBacked collection : collections) {
            if (!collection.restoreOnStartup()) {
                continue;
            }
            if (restore(collection)) {
                restored++;
            } else {
                failed++;
            }
        }
        log.info("[StoreBackup] restoreAll completed | backend={} | restored={} | failed={}",
                store.backendName(), restored, failed);
    }

    /**
     * Restores a single collection and, on success, opens its restore gate.
     *
     * @return true if the run completed without error. An empty store counts as success —
     *         having nothing stored yet is a valid state, not a failure.
     */
    public boolean restore(StoreBacked collection) {
        String storeId = collection.storeId();
        try {
            List<Map<String, String>> rows = store.load(storeId);
            long imported = collection.importRows(rows);
            restoreCompleted.put(storeId, Boolean.TRUE);
            log.info("[StoreBackup] restore | storeId={} | rowsInStore={} | imported={} | entityCount={}",
                    storeId, rows.size(), imported, safeCount(collection));
            return true;
        } catch (Exception e) { // NOSONAR - one collection must not bring down the others
            log.error("[StoreBackup] restore failed | storeId={} | error={}", storeId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Backs up a collection if the guard layer permits it.
     *
     * @param force bypasses the content hash — but neither the restore gate nor the empty
     *              guard — for changes that must not be lost
     * @return true if something was actually written
     */
    public boolean backup(StoreBacked collection, boolean force) {
        String storeId = collection.storeId();

        if (!Boolean.TRUE.equals(restoreCompleted.get(storeId))) {
            log.warn("[StoreBackup] backup skipped | storeId={} | reason=restore-not-completed", storeId);
            return false;
        }

        ReentrantLock lock = locks.computeIfAbsent(storeId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("[StoreBackup] backup skipped | storeId={} | reason=already-running", storeId);
            return false;
        }
        try {
            List<Map<String, String>> rows = collection.exportRows();

            if (rows == null || rows.isEmpty()) {
                log.warn("[StoreBackup] backup skipped | storeId={} | reason=empty | store-would-be-wiped", storeId);
                return false;
            }

            int hash = rows.hashCode();
            if (!force && Integer.valueOf(hash).equals(lastSavedHash.get(storeId))) {
                log.debug("[StoreBackup] backup skipped | storeId={} | reason=unchanged", storeId);
                return false;
            }

            store.save(storeId, rows);
            lastSavedHash.put(storeId, hash);
            log.info("[StoreBackup] backup | storeId={} | rows={} | force={} | backend={}",
                    storeId, rows.size(), force, store.backendName());
            return true;
        } catch (Exception e) { // NOSONAR - a failing backup must not propagate to the caller
            log.error("[StoreBackup] backup failed | storeId={} | error={}", storeId, e.getMessage(), e);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Backs up every collection — for a periodic run. */
    public void backupAll() {
        int written = 0;
        for (StoreBacked collection : collections) {
            if (backup(collection, false)) {
                written++;
            }
        }
        log.info("[StoreBackup] backupAll completed | backend={} | written={} | total={}",
                store.backendName(), written, collections.size());
    }

    /** The managed collections, sorted by {@code storeOrder} — for the admin overview. */
    public List<StoreBacked> getCollections() {
        return List.copyOf(collections);
    }

    /** Whether the collection was restored successfully in this process. */
    public boolean isRestoreCompleted(String storeId) {
        return Boolean.TRUE.equals(restoreCompleted.get(storeId));
    }

    private long safeCount(StoreBacked collection) {
        try {
            return collection.entityCount();
        } catch (Exception e) { // NOSONAR - logging detail only
            return -1L;
        }
    }
}

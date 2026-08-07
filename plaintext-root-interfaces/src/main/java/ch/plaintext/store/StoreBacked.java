/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.store;

import java.util.List;
import java.util.Map;

/**
 * Marks a collection that is backed up to a {@link TableStore} and restored on startup.
 *
 * <p>The entry point is an interface rather than a configuration table on purpose: the list
 * of backed-up collections then lives in code instead of in a table that would itself need
 * backing up. That removes the bootstrap problem entirely — there is no chicken-and-egg
 * chain — and whoever adds an entity decides about its persistence while writing it, rather
 * than in a config file nobody remembers.</p>
 *
 * <p>Implementations are collected by Spring via {@code List<StoreBacked>} injection, the
 * same way {@code PlaintextCron} implementations are discovered.</p>
 *
 * <p><b>Restoring stays with the implementer.</b> Existing stores show why a single generic
 * "load the table" would not do: one merges field by field (only filling roles that are
 * empty), another merges row by row (an existing key wins). This interface therefore hands
 * over the rows and leaves the merge semantics to the collection that owns them.</p>
 */
public interface StoreBacked {

    /**
     * Logical name of the collection. Backends derive their storage name from it — a wiki
     * backend, for instance, uses it as the title of the child page.
     */
    String storeId();

    /**
     * Restore order, lowest first. Collections referencing others belong further back.
     */
    default int storeOrder() {
        return 100;
    }

    /** Whether this collection is checked and restored during startup. */
    default boolean restoreOnStartup() {
        return true;
    }

    /** The current content as rows — the basis for a backup. */
    List<Map<String, String>> exportRows();

    /**
     * Applies previously backed-up rows. The semantics are up to the implementer:
     * replace, merge by row, or merge by field.
     *
     * @param rows rows as read from the store
     * @return number of rows actually applied, for logging
     */
    long importRows(List<Map<String, String>> rows);

    /**
     * Number of entries currently in the database. Used by the empty guard and to decide
     * whether a restore is needed at all.
     */
    long entityCount();
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.store;

import java.util.List;
import java.util.Map;

/**
 * A backing store for a collection of rows — the interchangeable half of table persistence.
 *
 * <p>The row format is deliberately generic: an arbitrarily wide table as
 * {@code List<Map<String, String>>}, with column names taken as the union over all rows.
 * Any backend can express that — a wiki page holding an HTML table, a CSV file, a second
 * database — which is what makes the store interchangeable.</p>
 *
 * <p>Implementations live where their infrastructure lives. The interface stays here so
 * that modules such as {@code plaintext-admin-settings} can declare what they want backed
 * up without depending on any particular storage technology.</p>
 *
 * <p>Blob support is intentionally absent for now. It is planned as
 * {@code putBlob}/{@code getBlob} returning a reference that the backend resolves on its
 * own terms — a page attachment where the backend has one, Base64 where it has not. It
 * will be added together with its first real consumer rather than kept as untested
 * inventory.</p>
 */
public interface TableStore {

    /**
     * Loads the rows of a collection.
     *
     * @param storeId logical name of the collection
     * @return the rows, or an empty list if the collection does not exist yet. Implementations
     *         must not throw for a missing collection — on a first run that is the normal case.
     */
    List<Map<String, String>> load(String storeId);

    /**
     * Writes the rows of a collection, replacing its previous content entirely.
     *
     * @param storeId logical name of the collection
     * @param rows    the rows to persist
     */
    void save(String storeId, List<Map<String, String>> rows);

    /** Short name of the backend for logging and the admin overview, e.g. {@code "confluence"}. */
    String backendName();
}

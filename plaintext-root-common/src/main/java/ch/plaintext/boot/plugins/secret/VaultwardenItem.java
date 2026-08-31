/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import java.util.Map;
import java.util.Optional;

/**
 * A decrypted Vaultwarden login item.
 *
 * @param id       cipher id (for a later rotation)
 * @param name     decrypted item name
 * @param username decrypted user name (may be {@code null})
 * @param password decrypted password (may be {@code null})
 * @param fields   decrypted custom fields (name → value)
 */
public record VaultwardenItem(
        String id,
        String name,
        String username,
        String password,
        Map<String, String> fields) {

    /** Custom field by name (case-insensitive). */
    public Optional<String> field(String fieldName) {
        if (fieldName == null || fields == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (fieldName.equalsIgnoreCase(e.getKey())) {
                return Optional.ofNullable(e.getValue());
            }
        }
        return Optional.empty();
    }
}

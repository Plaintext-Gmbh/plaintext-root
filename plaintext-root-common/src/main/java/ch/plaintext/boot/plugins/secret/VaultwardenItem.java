/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import java.util.Map;
import java.util.Optional;

/**
 * Ein entschluesseltes Vaultwarden-Login-Item.
 *
 * @param id       Cipher-Id (fuer spaetere Rotation)
 * @param name     entschluesselter Item-Name
 * @param username entschluesselter Benutzername (kann {@code null} sein)
 * @param password entschluesseltes Passwort (kann {@code null} sein)
 * @param fields   entschluesselte benutzerdefinierte Felder (Name → Wert)
 */
public record VaultwardenItem(
        String id,
        String name,
        String username,
        String password,
        Map<String, String> fields) {

    /** Benutzerdefiniertes Feld nach Namen (case-insensitive). */
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

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.selfservice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256-Hashing fuer die Einmal-Tokens der Selbstservice-Flows (Registrierung, Passwort-Reset).
 * Der Klartext-Token wird nur im E-Mail-Link ausgeliefert; in der DB liegt ausschliesslich der Hash
 * (Karte 307, K2.3) — analog {@code HashedOneTimeTokenService} fuer Magic-Links.
 */
final class SelfServiceTokenHash {

    private SelfServiceTokenHash() {
    }

    /** SHA-256 des Klartext-Tokens als 64-stelliger Hex-String (passt in VARCHAR(64)). */
    static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }
}

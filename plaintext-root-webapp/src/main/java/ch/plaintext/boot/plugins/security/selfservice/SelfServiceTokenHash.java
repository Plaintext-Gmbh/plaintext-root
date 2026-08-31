/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.selfservice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing for the one-time tokens of the self-service flows (registration, password reset).
 * The clear-text token is only delivered in the e-mail link; the DB holds exclusively the hash
 * (card 307, K2.3) — analogously to {@code HashedOneTimeTokenService} for magic links.
 */
final class SelfServiceTokenHash {

    private SelfServiceTokenHash() {
    }

    /** SHA-256 of the clear-text token as a 64-character hex string (fits into VARCHAR(64)). */
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

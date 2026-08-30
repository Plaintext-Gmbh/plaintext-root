/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashing + constant-time comparison for the Claude automation API tokens
 * ({@code /nosec/api/claude}).
 *
 * <p>On the server side only the SHA-256 hash of the token is compared (and, going forward,
 * stored) instead of the cleartext. All comparisons go through
 * {@link MessageDigest#isEqual(byte[], byte[])} and are therefore constant-time — an attacker
 * cannot derive any byte-by-byte information about the token from response times.</p>
 *
 * <p>Deliberately NO salt/BCrypt: the tokens are random UUIDs (~122 bits of entropy), not a
 * weak user password — a fast hash is sufficient, and the hash has to be deterministic for
 * the lookup.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class ApiTokenHasher {

    private ApiTokenHasher() {
        // Utility
    }

    /**
     * SHA-256 hash of the token as a lowercase hex string (64 characters).
     *
     * @param token raw token (cleartext)
     * @return hex hash, or {@code null} for {@code null} input
     */
    public static String sha256Hex(String token) {
        if (token == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JVM — we never end up here.
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }

    /**
     * Constant-time string comparison via {@link MessageDigest#isEqual(byte[], byte[])}.
     * {@code null}/{@code null} is NOT equal (token context: no token = no match).
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}

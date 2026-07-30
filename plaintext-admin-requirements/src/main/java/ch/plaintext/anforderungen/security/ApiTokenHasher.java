/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashing + konstantzeitiger Vergleich für die Claude-Automation-API-Tokens
 * ({@code /nosec/api/claude}).
 *
 * <p>Serverseitig wird nur noch der SHA-256-Hash des Tokens verglichen (und perspektivisch
 * gespeichert) statt des Klartexts. Alle Vergleiche laufen über
 * {@link MessageDigest#isEqual(byte[], byte[])} und sind damit konstantzeitig — ein Angreifer
 * kann aus Antwortzeiten keine Byte-für-Byte-Information über den Token ableiten.</p>
 *
 * <p>Bewusst KEIN Salt/BCrypt: die Tokens sind zufällige UUIDs (~122 Bit Entropie), kein
 * schwaches Nutzerpasswort — ein schneller Hash reicht, und der Hash muss für den
 * Lookup deterministisch sein.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class ApiTokenHasher {

    private ApiTokenHasher() {
        // Utility
    }

    /**
     * SHA-256-Hash des Tokens als lowercase-Hex-String (64 Zeichen).
     *
     * @param token roher Token (Klartext)
     * @return Hex-Hash, oder {@code null} für {@code null}-Input
     */
    public static String sha256Hex(String token) {
        if (token == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 ist in jeder JVM Pflicht — hier landen wir nie.
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }

    /**
     * Konstantzeitiger String-Vergleich via {@link MessageDigest#isEqual(byte[], byte[])}.
     * {@code null}/{@code null} ist NICHT gleich (Token-Kontext: kein Token = kein Match).
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}

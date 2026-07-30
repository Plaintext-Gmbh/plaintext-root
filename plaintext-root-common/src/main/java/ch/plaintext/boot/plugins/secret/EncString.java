/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import java.util.Arrays;
import java.util.Base64;

/**
 * Geparste Bitwarden-{@code EncString} (Cipher-String).
 *
 * <p>Format {@code "<type>.<payload>"}. Der Payload ist je Typ {@code |}-getrennt
 * und Base64-kodiert:</p>
 * <ul>
 *   <li>type 0: {@code iv|ct}                — AesCbc256_B64 (ohne MAC)</li>
 *   <li>type 1: {@code iv|ct|mac}            — AesCbc128_HmacSha256_B64</li>
 *   <li>type 2: {@code iv|ct|mac}            — AesCbc256_HmacSha256_B64 (Standard)</li>
 *   <li>type 3: {@code ct}                   — Rsa2048_OaepSha256_B64</li>
 *   <li>type 4: {@code ct}                   — Rsa2048_OaepSha1_B64 (Org-/User-Key-Wrapping)</li>
 *   <li>type 5/6: {@code ct|mac}             — Rsa2048_Oaep*_HmacSha256_B64</li>
 * </ul>
 *
 * @param type Typ-Kennung
 * @param iv   Initialisierungsvektor (nur type 0/1/2)
 * @param ct   Ciphertext
 * @param mac  MAC (nur type 1/2/5/6, sonst {@code null})
 */
record EncString(int type, byte[] iv, byte[] ct, byte[] mac) {

    /** Symmetrische AES-CBC-HMAC-Typen. */
    boolean isSymmetric() {
        return type == 1 || type == 2;
    }

    /** RSA-OAEP-Typen. */
    boolean isRsa() {
        return type == 3 || type == 4 || type == 5 || type == 6;
    }

    /**
     * Parst einen EncString. Fehlt der {@code "<type>."}-Prefix, wird anhand der
     * Anzahl {@code |}-Teile auf type 0 (iv|ct) bzw. type 2 (iv|ct|mac) geschlossen.
     *
     * @throws IllegalArgumentException bei leerem/kaputtem String
     */
    static EncString parse(String s) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("EncString ist leer");
        }
        int type;
        String payload;
        int dot = s.indexOf('.');
        if (dot >= 1 && dot <= 2 && isAllDigits(s, dot)) {
            type = Integer.parseInt(s.substring(0, dot));
            payload = s.substring(dot + 1);
        } else {
            // Legacy ohne Typ-Prefix: iv|ct (=0) oder iv|ct|mac (=2)
            payload = s;
            type = (countPipes(payload) >= 2) ? 2 : 0;
        }
        String[] parts = payload.split("\\|", -1);
        Base64.Decoder b64 = Base64.getDecoder();
        return switch (type) {
            case 0 -> new EncString(0, b64.decode(parts[0]), b64.decode(parts[1]), null);
            case 1, 2 -> new EncString(type,
                    b64.decode(parts[0]),
                    b64.decode(parts[1]),
                    parts.length > 2 ? b64.decode(parts[2]) : null);
            case 3, 4, 5, 6 -> new EncString(type,
                    null,
                    b64.decode(parts[0]),
                    parts.length > 1 ? b64.decode(parts[1]) : null);
            default -> throw new IllegalArgumentException("Unbekannter EncString-Typ: " + type);
        };
    }

    private static boolean isAllDigits(String s, int end) {
        for (int i = 0; i < end; i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return end > 0;
    }

    private static int countPipes(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '|') {
                n++;
            }
        }
        return n;
    }

    // Die vom Record generierten equals/hashCode/toString vergleichen Array-Felder ueber die
    // Referenz. Fuer wertsemantische Gleichheit (und um in toString KEINE Roh-Bytes zu leaken)
    // werden sie hier ueber den Array-Inhalt bzw. reine Laengen-Angaben ueberschrieben.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EncString(int otherType, byte[] otherIv, byte[] otherCt, byte[] otherMac))) {
            return false;
        }
        return type == otherType
                && Arrays.equals(iv, otherIv)
                && Arrays.equals(ct, otherCt)
                && Arrays.equals(mac, otherMac);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(type);
        result = 31 * result + Arrays.hashCode(iv);
        result = 31 * result + Arrays.hashCode(ct);
        result = 31 * result + Arrays.hashCode(mac);
        return result;
    }

    @Override
    public String toString() {
        return "EncString[type=" + type
                + ", iv=" + lengthOf(iv)
                + ", ct=" + lengthOf(ct)
                + ", mac=" + lengthOf(mac) + "]";
    }

    private static String lengthOf(byte[] a) {
        return a == null ? "null" : a.length + "B";
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reine Krypto-Primitiven fuer den Bitwarden/Vaultwarden-Protokoll-Flow.
 *
 * <p>Bewusst ohne externe Krypto-Bibliothek (kein BouncyCastle): bei KDF=0
 * (PBKDF2-SHA256) reicht die JCA vollstaendig aus. Alle Methoden sind statisch,
 * zustandslos und damit deterministisch unit-testbar (kein Netz, keine Secrets).</p>
 *
 * <p>Referenz-Protokoll (verifiziert gegen vault.example.org):</p>
 * <ol>
 *   <li>{@link #pbkdf2Sha256} — masterKey aus masterPassword + email</li>
 *   <li>{@link #pbkdf2Sha256} (1 Iteration) — masterPasswordHash</li>
 *   <li>{@link #hkdfExpandSha256} — stretched Key (enc||mac)</li>
 *   <li>{@link #decryptSymmetric} — EncString type 1/2 (AES-256-CBC + HMAC-SHA256)</li>
 *   <li>{@link #decryptRsaOaepSha1} — EncString type 4 (RSA-OAEP-SHA1)</li>
 * </ol>
 */
final class VaultwardenCrypto {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String HMAC_SHA256 = "HmacSHA256";

    private VaultwardenCrypto() {
    }

    /**
     * PBKDF2-HMAC-SHA256, eigene Implementierung ueber {@link Mac}.
     *
     * <p>Bewusst nicht ueber {@code SecretKeyFactory("PBKDF2WithHmacSHA256")}: die
     * SunJCE-Variante nimmt ein {@code char[]}-Passwort und interpretiert dessen
     * Bytes provider-abhaengig. Fuer den masterPasswordHash ist das "Passwort" der
     * rohe 32-Byte-masterKey, daher wird hier ausschliesslich mit {@code byte[]}
     * gearbeitet — 1:1 wie Pythons {@code hashlib.pbkdf2_hmac}.</p>
     *
     * @param password    Passwort-Bytes (bei Bitwarden UTF-8 des Master-Passworts bzw. der masterKey)
     * @param salt        Salt-Bytes (email in lowercase bzw. Master-Passwort)
     * @param iterations  Iterationen (KDF-Iterations, i.d.R. 600000 bzw. 1)
     * @param dkLenBytes  gewuenschte Ausgabelaenge in Bytes (32)
     */
    static byte[] pbkdf2Sha256(byte[] password, byte[] salt, int iterations, int dkLenBytes) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(password, HMAC_SHA256));
            final int hLen = 32;
            int blocks = (dkLenBytes + hLen - 1) / hLen;
            ByteArrayOutputStream out = new ByteArrayOutputStream(blocks * hLen);
            for (int i = 1; i <= blocks; i++) {
                // U1 = PRF(password, salt || INT_32_BE(i))
                mac.update(salt);
                mac.update(new byte[] {
                        (byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i
                });
                byte[] u = mac.doFinal();
                byte[] t = u.clone();
                for (int c = 1; c < iterations; c++) {
                    u = mac.doFinal(u);
                    for (int j = 0; j < t.length; j++) {
                        t[j] ^= u[j];
                    }
                }
                out.write(t, 0, t.length);
            }
            return Arrays.copyOf(out.toByteArray(), dkLenBytes);
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2-SHA256 fehlgeschlagen", e);
        }
    }

    /**
     * HKDF-Expand mit SHA-256 (RFC 5869). PRK wird direkt verwendet (kein Extract,
     * wie in Bitwardens {@code stretchKey}). Da {@code outLen <= 32} ist genau ein
     * Block noetig: {@code T(1) = HMAC(prk, info || 0x01)}.
     *
     * @param prk    Pseudo-Random-Key (masterKey, 32B)
     * @param info   Kontext-String ("enc" bzw. "mac")
     * @param outLen Ausgabelaenge in Bytes (<= 32)
     */
    static byte[] hkdfExpandSha256(byte[] prk, String info, int outLen) {
        if (outLen > 32) {
            throw new IllegalArgumentException("hkdfExpandSha256 unterstuetzt nur outLen <= 32");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(prk, HMAC_SHA256));
            mac.update(info.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0x01);
            return Arrays.copyOf(mac.doFinal(), outLen);
        } catch (Exception e) {
            throw new IllegalStateException("HKDF-Expand-SHA256 fehlgeschlagen", e);
        }
    }

    /**
     * Bildet den 64-Byte "stretched key" aus dem masterKey:
     * {@code HKDF-Expand(masterKey,"enc",32) || HKDF-Expand(masterKey,"mac",32)}.
     */
    static byte[] stretchMasterKey(byte[] masterKey) {
        byte[] enc = hkdfExpandSha256(masterKey, "enc", 32);
        byte[] mac = hkdfExpandSha256(masterKey, "mac", 32);
        byte[] out = new byte[64];
        System.arraycopy(enc, 0, out, 0, 32);
        System.arraycopy(mac, 0, out, 32, 32);
        return out;
    }

    /**
     * Entschluesselt eine symmetrische {@link EncString} (type 1/2) mit einem
     * 64-Byte-Schluessel (enc = [0..32), mac = [32..64)).
     *
     * <p>Verifiziert zuerst {@code HMAC-SHA256(iv || ct)} gegen den mitgelieferten
     * MAC (constant-time), erst danach AES-256-CBC/PKCS7.</p>
     *
     * @throws IllegalStateException bei MAC-Mismatch oder falschem EncString-Typ
     */
    static byte[] decryptSymmetric(EncString enc, byte[] key64) {
        if (!enc.isSymmetric()) {
            throw new IllegalStateException("decryptSymmetric erwartet EncString type 1/2, war type " + enc.type());
        }
        if (key64 == null || key64.length < 64) {
            throw new IllegalStateException("symmetrischer Schluessel muss 64 Byte sein");
        }
        byte[] encKey = Arrays.copyOfRange(key64, 0, 32);
        byte[] macKey = Arrays.copyOfRange(key64, 32, 64);
        try {
            // MAC pruefen (constant-time)
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(macKey, HMAC_SHA256));
            mac.update(enc.iv());
            mac.update(enc.ct());
            byte[] computed = mac.doFinal();
            if (enc.mac() == null || !MessageDigest.isEqual(computed, enc.mac())) {
                throw new IllegalStateException("MAC-Verifikation fehlgeschlagen");
            }
            // NOSONAR: AES-256-CBC ist vom Bitwarden/Vaultwarden-EncString-Protokoll (type 2)
            // fest vorgegeben; der Cipher-Mode ist nicht frei waehlbar (GCM waere inkompatibel).
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); // NOSONAR
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(enc.iv()));
            return cipher.doFinal(enc.ct());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("symmetrische Entschluesselung fehlgeschlagen", e);
        }
    }

    /**
     * Verschluesselt {@code plaintext} zu einer symmetrischen {@link EncString}
     * type 2 (AesCbc256_HmacSha256_B64) — Gegenstueck zu {@link #decryptSymmetric}.
     *
     * <p>Verfahren (Bitwarden-kompatibel):</p>
     * <ol>
     *   <li>zufaelliger 16-Byte-IV (SecureRandom)</li>
     *   <li>{@code ct = AES-256-CBC/PKCS7(key[0..32), IV, plaintext)}</li>
     *   <li>{@code mac = HMAC-SHA256(key[32..64), IV || ct)}</li>
     *   <li>Ergebnis {@code "2." + b64(IV) + "|" + b64(ct) + "|" + b64(mac)}</li>
     * </ol>
     *
     * <p>Der Round-Trip {@code decryptSymmetric(parse(encryptSymmetric(p, k)), k) == p}
     * gilt fuer jeden 64-Byte-Schluessel {@code k}.</p>
     *
     * @param plaintext Klartext-Bytes (z.B. UTF-8 eines neuen Passworts)
     * @param key64     64-Byte-Schluessel (enc = [0..32), mac = [32..64))
     * @return EncString type 2 als String
     * @throws IllegalStateException bei falscher Schluessellaenge oder Krypto-Fehler
     */
    static String encryptSymmetric(byte[] plaintext, byte[] key64) {
        if (key64 == null || key64.length < 64) {
            throw new IllegalStateException("symmetrischer Schluessel muss 64 Byte sein");
        }
        byte[] encKey = Arrays.copyOfRange(key64, 0, 32);
        byte[] macKey = Arrays.copyOfRange(key64, 32, 64);
        try {
            byte[] iv = new byte[16];
            SECURE_RANDOM.nextBytes(iv);

            // NOSONAR: AES-256-CBC ist vom Bitwarden/Vaultwarden-EncString-Protokoll (type 2)
            // fest vorgegeben; der Cipher-Mode ist nicht frei waehlbar (GCM waere inkompatibel).
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); // NOSONAR
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
            byte[] ct = cipher.doFinal(plaintext);

            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(macKey, HMAC_SHA256));
            mac.update(iv);
            mac.update(ct);
            byte[] macTag = mac.doFinal();

            Base64.Encoder b64 = Base64.getEncoder();
            return "2." + b64.encodeToString(iv) + "|" + b64.encodeToString(ct) + "|" + b64.encodeToString(macTag);
        } catch (Exception e) {
            throw new IllegalStateException("symmetrische Verschluesselung fehlgeschlagen", e);
        }
    }

    /**
     * Entschluesselt eine RSA-{@link EncString} (type 4) mit RSA-OAEP-SHA1
     * (MGF1-SHA1) — so wickelt Bitwarden Org-/User-Keys.
     */
    static byte[] decryptRsaOaepSha1(EncString enc, PrivateKey privateKey) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(enc.ct());
        } catch (Exception e) {
            throw new IllegalStateException("RSA-OAEP-SHA1-Entschluesselung fehlgeschlagen", e);
        }
    }

    /**
     * Baut einen RSA-PrivateKey aus DER-kodiertem PKCS#8 (Ergebnis der
     * Profile.PrivateKey-Entschluesselung).
     */
    static PrivateKey rsaPrivateKeyFromPkcs8(byte[] der) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("RSA-PrivateKey konnte nicht geladen werden", e);
        }
    }
}

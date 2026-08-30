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
 * Pure crypto primitives for the Bitwarden/Vaultwarden protocol flow.
 *
 * <p>Deliberately without an external crypto library (no BouncyCastle): with KDF=0
 * (PBKDF2-SHA256) the JCA is entirely sufficient. All methods are static,
 * stateless and therefore deterministically unit-testable (no network, no secrets).</p>
 *
 * <p>Reference protocol (verified against vault.example.org):</p>
 * <ol>
 *   <li>{@link #pbkdf2Sha256} — masterKey from masterPassword + email</li>
 *   <li>{@link #pbkdf2Sha256} (1 iteration) — masterPasswordHash</li>
 *   <li>{@link #hkdfExpandSha256} — stretched key (enc||mac)</li>
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
     * PBKDF2-HMAC-SHA256, implemented here on top of {@link Mac}.
     *
     * <p>Deliberately not via {@code SecretKeyFactory("PBKDF2WithHmacSHA256")}: the
     * SunJCE variant takes a {@code char[]} password and interprets its bytes in a
     * provider-dependent way. For the masterPasswordHash the "password" is the raw
     * 32-byte masterKey, which is why everything here works with {@code byte[]}
     * only — exactly like Python's {@code hashlib.pbkdf2_hmac}.</p>
     *
     * @param password    password bytes (with Bitwarden the UTF-8 of the master password resp. the masterKey)
     * @param salt        salt bytes (email in lowercase resp. the master password)
     * @param iterations  iterations (KDF iterations, usually 600000 resp. 1)
     * @param dkLenBytes  desired output length in bytes (32)
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
     * HKDF-Expand with SHA-256 (RFC 5869). The PRK is used directly (no extract,
     * as in Bitwarden's {@code stretchKey}). Since {@code outLen <= 32}, exactly one
     * block is needed: {@code T(1) = HMAC(prk, info || 0x01)}.
     *
     * @param prk    pseudo random key (masterKey, 32B)
     * @param info   context string ("enc" resp. "mac")
     * @param outLen output length in bytes (<= 32)
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
     * Builds the 64-byte "stretched key" from the masterKey:
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
     * Decrypts a symmetric {@link EncString} (type 1/2) with a
     * 64-byte key (enc = [0..32), mac = [32..64)).
     *
     * <p>First verifies {@code HMAC-SHA256(iv || ct)} against the supplied
     * MAC (constant-time), and only then AES-256-CBC/PKCS7.</p>
     *
     * @throws IllegalStateException on a MAC mismatch or a wrong EncString type
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
            // verify the MAC (constant-time)
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(macKey, HMAC_SHA256));
            mac.update(enc.iv());
            mac.update(enc.ct());
            byte[] computed = mac.doFinal();
            if (enc.mac() == null || !MessageDigest.isEqual(computed, enc.mac())) {
                throw new IllegalStateException("MAC-Verifikation fehlgeschlagen");
            }
            // NOSONAR: AES-256-CBC is prescribed by the Bitwarden/Vaultwarden EncString protocol
            // (type 2); the cipher mode cannot be chosen freely (GCM would be incompatible).
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
     * Encrypts {@code plaintext} into a symmetric {@link EncString}
     * of type 2 (AesCbc256_HmacSha256_B64) — counterpart to {@link #decryptSymmetric}.
     *
     * <p>Procedure (Bitwarden compatible):</p>
     * <ol>
     *   <li>random 16-byte IV (SecureRandom)</li>
     *   <li>{@code ct = AES-256-CBC/PKCS7(key[0..32), IV, plaintext)}</li>
     *   <li>{@code mac = HMAC-SHA256(key[32..64), IV || ct)}</li>
     *   <li>result {@code "2." + b64(IV) + "|" + b64(ct) + "|" + b64(mac)}</li>
     * </ol>
     *
     * <p>The round trip {@code decryptSymmetric(parse(encryptSymmetric(p, k)), k) == p}
     * holds for every 64-byte key {@code k}.</p>
     *
     * @param plaintext plaintext bytes (e.g. the UTF-8 of a new password)
     * @param key64     64-byte key (enc = [0..32), mac = [32..64))
     * @return EncString type 2 as a string
     * @throws IllegalStateException on a wrong key length or a crypto error
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

            // NOSONAR: AES-256-CBC is prescribed by the Bitwarden/Vaultwarden EncString protocol
            // (type 2); the cipher mode cannot be chosen freely (GCM would be incompatible).
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
     * Decrypts an RSA {@link EncString} (type 4) with RSA-OAEP-SHA1
     * (MGF1-SHA1) — that is how Bitwarden wraps org/user keys.
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
     * Builds an RSA private key from DER-encoded PKCS#8 (the result of the
     * Profile.PrivateKey decryption).
     */
    static PrivateKey rsaPrivateKeyFromPkcs8(byte[] der) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("RSA-PrivateKey konnte nicht geladen werden", e);
        }
    }
}

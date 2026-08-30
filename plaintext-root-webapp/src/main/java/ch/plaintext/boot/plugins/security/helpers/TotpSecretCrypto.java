/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.helpers;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for TOTP secrets at rest (status report 29.08.2026).
 *
 * <p>Format in the column: {@code enc1:} + Base64(IV[12] || ciphertext || tag[16]). Values without
 * the prefix are legacy plaintext and are read unchanged; on the next write
 * they are encrypted. This way no big-bang migration is needed — and a deployment without
 * a key still reads all the old secrets.</p>
 *
 * <p>A static holder, because JPA {@code AttributeConverter}s do not get Spring beans
 * injected; {@code TotpSecretCryptoInitializer} sets the key at startup from
 * {@code plaintext.security.totp-encryption-key} (fallback: remember-me-key).</p>
 *
 * @author info@plaintext.ch
 * @since 1.636.0
 */
@Slf4j
public final class TotpSecretCrypto {

    static final String PREFIX = "enc1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile byte[] key;
    private static volatile boolean klartextGewarnt;

    private TotpSecretCrypto() {
    }

    /**
     * Sets the key material (any character string; it is reduced to 32 bytes via SHA-256).
     * {@code null}/empty switches the encryption off — values are then written in plaintext,
     * with a one-off warning.
     */
    public static void configure(String schluesselMaterial) {
        if (schluesselMaterial == null || schluesselMaterial.isBlank()) {
            key = null;
            return;
        }
        try {
            key = MessageDigest.getInstance("SHA-256")
                    .digest(schluesselMaterial.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }

    public static boolean isConfigured() {
        return key != null;
    }

    /** For the transition between tests. */
    static void reset() {
        key = null;
        klartextGewarnt = false;
    }

    public static String encrypt(String klartext) {
        if (klartext == null) {
            return null;
        }
        byte[] k = key;
        if (k == null) {
            if (!klartextGewarnt) {
                klartextGewarnt = true;
                log.warn("TOTP-Secrets werden im KLARTEXT gespeichert: weder "
                        + "plaintext.security.totp-encryption-key noch remember-me-key gesetzt "
                        + "(in PROD ist der remember-me-key Pflicht, dort passiert das nicht).");
            }
            return klartext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(klartext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP-Secret konnte nicht verschluesselt werden", e);
        }
    }

    public static String decrypt(String gespeichert) {
        if (gespeichert == null || !gespeichert.startsWith(PREFIX)) {
            return gespeichert;   // legacy plaintext
        }
        byte[] k = key;
        if (k == null) {
            throw new IllegalStateException("TOTP-Secret ist verschluesselt, aber kein Schluessel "
                    + "konfiguriert (plaintext.security.totp-encryption-key / remember-me-key)");
        }
        try {
            byte[] in = Base64.getDecoder().decode(gespeichert.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"),
                    new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            byte[] pt = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("TOTP-Secret konnte nicht entschluesselt werden "
                    + "(falscher Schluessel? Rotation ohne Neu-Einrichtung?)", e);
        }
    }
}

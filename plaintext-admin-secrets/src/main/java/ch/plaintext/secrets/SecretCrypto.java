/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM encryption/decryption for locally stored secrets and the backend access token.
 *
 * <p>The key comes from the env variable {@code PLAINTEXT_SECRET_KEY} (base64, 32 bytes = AES-256) —
 * the ONLY secret injected via Docker env, random per app/instance. If it is missing/invalid, a
 * deterministic dev fallback key is used (with a loud warning, ONLY for dev/test — in PROD the env
 * MUST be set, otherwise locally stored secrets would be insecure/not portable).</p>
 */
@Slf4j
@Component
public class SecretCrypto {

    private static final String ENV_KEY = "PLAINTEXT_SECRET_KEY";
    private static final int IV_LEN = 12;         // GCM recommendation: 96 bit
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKeySpec key;
    private final boolean devFallback;

    public SecretCrypto(Environment environment) {
        this(environment != null && isProduction(environment));
    }

    /** Test constructor: forces the dev fallback without a Spring context. */
    SecretCrypto() {
        this(false);
    }

    private SecretCrypto(boolean production) {
        byte[] raw = ladeKey();
        this.devFallback = raw == null;
        if (devFallback) {
            // SECURITY (card 314, item 8): the dev fallback derives the key from
            // sha256("plaintext-dev-fallback-" + HOSTNAME). If HOSTNAME is not set
            // (neither the Dockerfile nor compose.yaml set the variable), the value is the
            // constant string "null" and the key is therefore publicly computable —
            // anyone who gets hold of the stored secrets can decrypt them.
            // In PROD therefore fail fast at startup instead of an easily overlooked WARN.
            if (production) {
                throw new IllegalStateException(ENV_KEY + " ist in PROD Pflicht (base64, 32 Byte). "
                        + "Der deterministische Dev-Fallback-Schluessel ist oeffentlich berechenbar "
                        + "und darf nicht fuer produktive Secrets verwendet werden.");
            }
            log.warn("{} nicht gesetzt — verwende DETERMINISTISCHEN Dev-Fallback-Key. NUR fuer Dev/Test! "
                    + "In PROD {} als base64(32 Byte) per Env setzen.", ENV_KEY, ENV_KEY);
            raw = sha256(("plaintext-dev-fallback-" + System.getenv("HOSTNAME")).getBytes(StandardCharsets.UTF_8));
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Production environment = active Spring profile {@code prod} (that is how the Dockerfile sets it). */
    private static boolean isProduction(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] ladeKey() {
        String b64 = System.getenv(ENV_KEY);
        if (b64 == null || b64.isBlank()) {
            return null;
        }
        try {
            byte[] k = Base64.getDecoder().decode(b64.trim());
            return k.length == 32 ? k : sha256(k);   // tolerate deviating lengths via SHA-256
        } catch (RuntimeException e) {
            log.warn("{} ist kein gueltiges base64 — Dev-Fallback.", ENV_KEY);
            return null;
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** true if NO real env key is set (UI warning). */
    public boolean isDevFallback() {
        return devFallback;
    }

    /** Plaintext → base64(iv||ciphertext||tag). */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Secret-Verschluesselung fehlgeschlagen", e);
        }
    }

    /** base64(iv||ciphertext||tag) → plaintext. */
    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Secret-Entschluesselung fehlgeschlagen (falscher Key?)", e);
        }
    }
}

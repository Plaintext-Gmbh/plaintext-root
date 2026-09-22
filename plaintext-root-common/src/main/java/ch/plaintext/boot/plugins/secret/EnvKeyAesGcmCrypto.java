/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption/decryption with the key from the env variable
 * {@code PLAINTEXT_SECRET_KEY} — the single implementation behind every module that stores
 * recoverable secrets at rest.
 *
 * <p>The key is base64 of 32 bytes (AES-256), the ONLY secret injected via Docker env, random per
 * app/instance. If it is missing/invalid, a deterministic dev fallback key is used (with a loud
 * warning, ONLY for dev/test — in PROD the env MUST be set, otherwise locally stored secrets would
 * be insecure/not portable).</p>
 *
 * <p><b>SECURITY (card 376, originally item 8 of collective card 314).</b> The dev fallback derives
 * the key from {@code sha256("plaintext-dev-fallback-" + HOSTNAME)}. HOSTNAME is set neither in the
 * Dockerfile nor in compose.yaml — the value is then the constant string "null" and the key is
 * therefore publicly computable. Anyone who gets hold of the encrypted material can read it. In
 * PROD this is therefore a startup error instead of an easily overlooked WARN.</p>
 *
 * <p><b>Why this class exists (card 1301).</b> Until 22.09.2026 this hardening lived in two
 * hand-synchronised copies: {@code SecretCrypto} (plaintext-admin-secrets) and
 * {@code WebhookCrypto} (plaintext-admin-webhooks). They had already drifted — only the former
 * tracked {@link #isDevFallback()}, so the webhook signing secrets ran on a publicly computable key
 * without any way for the UI to say so. Both modules already depend on
 * {@code plaintext-root-common}, so merging the hardening here costs no new dependency. Subclasses
 * remain separate Spring beans (separate purposes, separate error messages, separately mockable);
 * only the crypto and the key handling are shared.</p>
 *
 * <p>Wire format is unchanged and identical for every subclass: {@code base64(iv||ciphertext||tag)}
 * with a 12-byte IV and a 128-bit tag. Material encrypted by one subclass is therefore readable by
 * any other subclass holding the same key — {@code KryptoHaertungVertragTest} (plaintext-root-webapp)
 * pins that.</p>
 */
@Slf4j
public abstract class EnvKeyAesGcmCrypto {

    /** The env variable holding base64(32 bytes). Shared by every subclass on purpose. */
    protected static final String ENV_KEY = "PLAINTEXT_SECRET_KEY";

    private static final int IV_LEN = 12;         // GCM recommendation: 96 bit
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKeySpec key;
    private final boolean devFallback;
    private final String zweck;

    /**
     * @param production {@code true} = fail fast instead of the computable dev fallback key
     * @param zweck      short label for the log/exception texts, e.g. {@code "Secret"}
     */
    protected EnvKeyAesGcmCrypto(boolean production, String zweck) {
        this.zweck = zweck;
        byte[] raw = ladeKey();
        this.devFallback = raw == null;
        if (devFallback) {
            if (production) {
                throw new IllegalStateException(ENV_KEY + " ist in PROD Pflicht (base64, 32 Byte). "
                        + "Der deterministische Dev-Fallback-Schluessel ist oeffentlich berechenbar "
                        + "und darf nicht fuer produktive " + zweck + "s verwendet werden.");
            }
            log.warn("{} nicht gesetzt — verwende DETERMINISTISCHEN Dev-Fallback-Key. NUR fuer Dev/Test! "
                    + "In PROD {} als base64(32 Byte) per Env setzen.", ENV_KEY, ENV_KEY);
            raw = sha256(("plaintext-dev-fallback-" + System.getenv("HOSTNAME")).getBytes(StandardCharsets.UTF_8));
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Production environment = active Spring profile {@code prod} (that is how the Dockerfile sets it). */
    protected static boolean isProduction(Environment environment) {
        if (environment == null) {
            return false;
        }
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

    /**
     * true if NO real env key is set, i.e. the publicly computable dev fallback is in use
     * (UI warning). Was only available on {@code SecretCrypto} before card 1301.
     */
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
            throw new IllegalStateException(zweck + "-Verschluesselung fehlgeschlagen", e);
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
            throw new IllegalStateException(zweck + "-Entschluesselung fehlgeschlagen (falscher Key?)", e);
        }
    }
}

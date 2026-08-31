/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.service;

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
 * AES-256-GCM encryption/decryption for the webhook signing secrets (unlike a pure password hash
 * they must be recoverable in order to sign every outgoing request, hence encryption instead of
 * hashing). Same pattern + same env key as {@code plaintext-admin-secrets}' {@code SecretCrypto},
 * duplicated here because no module references {@code plaintext-admin-secrets} across module
 * boundaries.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Component
public class WebhookCrypto {

    private static final String ENV_KEY = "PLAINTEXT_SECRET_KEY";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKeySpec key;

    public WebhookCrypto(Environment environment) {
        this(environment != null && isProduction(environment));
    }

    /** Test constructor: forces the dev fallback without a Spring context. */
    WebhookCrypto() {
        this(false);
    }

    private WebhookCrypto(boolean production) {
        byte[] raw = ladeKey();
        if (raw == null) {
            // SECURITY (card 376, originally item 8 of collective card 314): The dev fallback
            // derives the key from sha256("plaintext-dev-fallback-" + HOSTNAME). HOSTNAME is set
            // neither in the Dockerfile nor in compose.yaml — the value is then the constant
            // string "null" and the key is therefore publicly computable. Anyone who gets hold of
            // the encrypted webhook signing secrets can read them and subsequently produce valid
            // signatures.
            //
            // The same check exists in SecretCrypto (plaintext-admin-secrets). It is duplicated
            // here because no module references that one across module boundaries — a fix in only
            // one of the two classes would leave the hole open.
            if (production) {
                throw new IllegalStateException(ENV_KEY + " ist in PROD Pflicht (base64, 32 Byte). "
                        + "Der deterministische Dev-Fallback-Schluessel ist oeffentlich berechenbar "
                        + "und darf nicht fuer produktive Webhook-Secrets verwendet werden.");
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
            return k.length == 32 ? k : sha256(k);
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

    /** Plain text → base64(iv||ciphertext||tag). */
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
            throw new IllegalStateException("Webhook-Secret-Verschluesselung fehlgeschlagen", e);
        }
    }

    /** base64(iv||ciphertext||tag) → plain text. */
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
            throw new IllegalStateException("Webhook-Secret-Entschluesselung fehlgeschlagen (falscher Key?)", e);
        }
    }
}

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
 * AES-256-GCM Ver-/Entschlüsselung für die Webhook-Signing-Secrets (müssen — anders als ein reines
 * Passwort-Hash — zum Signieren jedes ausgehenden Requests wiederherstellbar sein, daher
 * Verschlüsselung statt Hashing). Gleiches Muster + gleicher Env-Key wie
 * {@code plaintext-admin-secrets}' {@code SecretCrypto}, hier dupliziert, da kein Modul quer auf
 * {@code plaintext-admin-secrets} referenziert.
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

    /** Test-Konstruktor: erzwingt den Dev-Fallback ohne Spring-Kontext. */
    WebhookCrypto() {
        this(false);
    }

    private WebhookCrypto(boolean production) {
        byte[] raw = ladeKey();
        if (raw == null) {
            // SECURITY (Karte 376, urspruenglich Punkt 8 der Sammelkarte 314): Der Dev-Fallback
            // leitet den Schluessel aus sha256("plaintext-dev-fallback-" + HOSTNAME) ab. HOSTNAME
            // wird weder im Dockerfile noch in der compose.yaml gesetzt — der Wert ist dann der
            // konstante String "null" und der Schluessel damit oeffentlich berechenbar. Wer die
            // verschluesselten Webhook-Signing-Secrets in die Haende bekommt, kann sie lesen und
            // anschliessend gueltige Signaturen erzeugen.
            //
            // Dieselbe Pruefung steht in SecretCrypto (plaintext-admin-secrets). Sie ist hier
            // dupliziert, weil kein Modul quer auf jenes referenziert — ein Fix in nur einer der
            // beiden Klassen liesse die Luecke offen.
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

    /** Produktivumgebung = aktives Spring-Profil {@code prod} (so setzt es das Dockerfile). */
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

    /** Klartext → base64(iv||ciphertext||tag). */
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

    /** base64(iv||ciphertext||tag) → Klartext. */
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

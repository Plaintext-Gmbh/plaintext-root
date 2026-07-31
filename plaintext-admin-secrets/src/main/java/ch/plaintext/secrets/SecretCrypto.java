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
 * AES-GCM Ver-/Entschlüsselung für lokal abgelegte Secrets und das Backend-Zugriffstoken.
 *
 * <p>Der Schlüssel kommt aus der Env-Variable {@code PLAINTEXT_SECRET_KEY} (base64, 32 Byte =
 * AES-256) — das EINZIGE per Docker-Env injizierte Secret, pro App/Instanz zufällig. Fehlt/ungültig,
 * wird ein deterministischer Dev-Fallback-Key benutzt (laut Warnung, NUR für Dev/Test — in PROD MUSS
 * die Env gesetzt sein, sonst wären lokal abgelegte Secrets unsicher/nicht portabel).</p>
 */
@Slf4j
@Component
public class SecretCrypto {

    private static final String ENV_KEY = "PLAINTEXT_SECRET_KEY";
    private static final int IV_LEN = 12;         // GCM empfohlen: 96 bit
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKeySpec key;
    private final boolean devFallback;

    public SecretCrypto(Environment environment) {
        this(environment != null && isProduction(environment));
    }

    /** Test-Konstruktor: erzwingt den Dev-Fallback ohne Spring-Kontext. */
    SecretCrypto() {
        this(false);
    }

    private SecretCrypto(boolean production) {
        byte[] raw = ladeKey();
        this.devFallback = raw == null;
        if (devFallback) {
            // SECURITY (Karte 314, Punkt 8): der Dev-Fallback leitet den Schluessel aus
            // sha256("plaintext-dev-fallback-" + HOSTNAME) ab. Ist HOSTNAME nicht gesetzt
            // (weder Dockerfile noch compose.yaml setzen die Variable), ist der Wert der
            // konstante String "null" und der Schluessel damit oeffentlich berechenbar —
            // jeder, der die abgelegten Secrets in die Haende bekommt, kann sie entschluesseln.
            // In PROD deshalb Fail-Fast beim Start statt einer leicht zu uebersehenden WARN.
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
            return k.length == 32 ? k : sha256(k);   // toleriere abweichende Längen via SHA-256
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

    /** true, wenn KEIN echter Env-Key gesetzt ist (UI-Warnung). */
    public boolean isDevFallback() {
        return devFallback;
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
            throw new IllegalStateException("Secret-Verschluesselung fehlgeschlagen", e);
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
            throw new IllegalStateException("Secret-Entschluesselung fehlgeschlagen (falscher Key?)", e);
        }
    }
}

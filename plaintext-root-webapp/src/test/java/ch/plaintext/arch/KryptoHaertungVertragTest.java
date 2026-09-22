/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import ch.plaintext.secrets.SecretCrypto;
import ch.plaintext.webhooks.service.WebhookCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for the AES-256-GCM hardening that used to live in <b>two</b> hand-synchronised
 * copies (card 1301, from card 1274 item 8): {@code SecretCrypto} (plaintext-admin-secrets) and
 * {@code WebhookCrypto} (plaintext-admin-webhooks). Both now extend
 * {@code ch.plaintext.boot.plugins.secret.EnvKeyAesGcmCrypto} in plaintext-root-common.
 *
 * <p>This module is the only one that sees both classes, which is why the test lives here and not
 * next to either of them.</p>
 *
 * <p>What it pins:</p>
 * <ol>
 *   <li><b>Altbestand.</b> Ciphertext produced by the pre-1301 code — reproduced here by a verbatim
 *       reference implementation of the old {@code encrypt()} plus the old key derivation — still
 *       decrypts with both classes. Encrypted holdings stay readable.</li>
 *   <li><b>One format.</b> Whatever one class writes, the other reads: the two copies really did
 *       share the wire format, and merging them did not change it.</li>
 *   <li><b>The merged version is the stronger one.</b> The copies had drifted: only
 *       {@code SecretCrypto} tracked {@code isDevFallback()}, so nothing could report that the
 *       webhook signing secrets sat on a publicly computable key. Both report it now.</li>
 *   <li><b>Fail-fast in PROD stays red</b> for both call sites (card 376).</li>
 * </ol>
 */
@DisplayName("Krypto-Haertung: eine Fassung, beide Aufrufstellen")
class KryptoHaertungVertragTest {

    private static final String ENV_KEY = "PLAINTEXT_SECRET_KEY";

    private static boolean echterKeyGesetzt() {
        String b64 = System.getenv(ENV_KEY);
        return b64 != null && !b64.isBlank();
    }

    // ------------------------------------------------------------------
    // Reference implementation: the pre-1301 code, copied verbatim.
    // It must stay frozen — it represents what is already in the database.
    // ------------------------------------------------------------------

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Old {@code ladeKey()} incl. the deterministic dev fallback. */
    private static SecretKeySpec altKey() {
        byte[] raw = null;
        String b64 = System.getenv(ENV_KEY);
        if (b64 != null && !b64.isBlank()) {
            try {
                byte[] k = Base64.getDecoder().decode(b64.trim());
                raw = k.length == 32 ? k : sha256(k);
            } catch (RuntimeException e) {
                raw = null;
            }
        }
        if (raw == null) {
            raw = sha256(("plaintext-dev-fallback-" + System.getenv("HOSTNAME")).getBytes(StandardCharsets.UTF_8));
        }
        return new SecretKeySpec(raw, "AES");
    }

    /** Old {@code encrypt()}: base64(iv(12) || ciphertext || tag(128 bit)). */
    private static String altVerschluesselt(String klartext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, altKey(), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(klartext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Altbestand aus der Zeit vor der Zusammenlegung bleibt lesbar")
    void altbestandBleibtLesbar() {
        String altSecret = altVerschluesselt("bestandswert-secret");
        String altWebhook = altVerschluesselt("bestandswert-webhook");

        assertEquals("bestandswert-secret", new SecretCrypto(new MockEnvironment()).decrypt(altSecret));
        assertEquals("bestandswert-webhook", new WebhookCrypto(new MockEnvironment()).decrypt(altWebhook));
    }

    @Test
    @DisplayName("Beide Aufrufstellen schreiben und lesen dasselbe Format")
    void beideAufrufstellenTeilenDasFormat() {
        SecretCrypto secret = new SecretCrypto(new MockEnvironment());
        WebhookCrypto webhook = new WebhookCrypto(new MockEnvironment());

        assertEquals("quer", webhook.decrypt(secret.encrypt("quer")));
        assertEquals("quer", secret.decrypt(webhook.encrypt("quer")));
    }

    @Test
    @DisplayName("Formatvertrag: 12-Byte-IV, 128-Bit-Tag, frischer IV je Aufruf")
    void formatvertragUnveraendert() {
        SecretCrypto secret = new SecretCrypto(new MockEnvironment());

        byte[] a = Base64.getDecoder().decode(secret.encrypt(""));
        // leerer Klartext: 12 Byte IV + 0 Byte Chiffrat + 16 Byte Tag
        assertEquals(28, a.length);

        assertTrue(!secret.encrypt("gleich").equals(secret.encrypt("gleich")),
                "gleicher Klartext muss zwei verschiedene Chiffrate ergeben (frischer IV, kein ECB)");
    }

    @Test
    @DisplayName("Die zusammengelegte Fassung ist die staerkere: isDevFallback gilt jetzt auch fuer Webhooks")
    void zusammengelegteFassungIstDieStaerkere() {
        boolean erwartet = !echterKeyGesetzt();

        assertEquals(erwartet, new SecretCrypto(new MockEnvironment()).isDevFallback(),
                "SecretCrypto meldete den berechenbaren Dev-Key schon vorher");
        assertEquals(erwartet, new WebhookCrypto(new MockEnvironment()).isDevFallback(),
                "WebhookCrypto konnte das vor Karte 1301 gar nicht — genau das war die Abweichung");
    }

    @Test
    @DisplayName("Fail-Fast in PROD bleibt an beiden Aufrufstellen rot")
    void failFastInProdGiltFuerBeide() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        if (echterKeyGesetzt()) {
            assertDoesNotThrow(() -> new SecretCrypto(prod));
            assertDoesNotThrow(() -> new WebhookCrypto(prod));
            return;
        }

        assertTrue(assertThrows(IllegalStateException.class, () -> new SecretCrypto(prod))
                .getMessage().contains(ENV_KEY));
        assertTrue(assertThrows(IllegalStateException.class, () -> new WebhookCrypto(prod))
                .getMessage().contains(ENV_KEY));
    }
}

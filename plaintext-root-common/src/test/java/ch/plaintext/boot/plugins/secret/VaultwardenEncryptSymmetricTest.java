/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/**
 * Deterministische Tests fuer die Schreibrichtung (Rotation): {@code encryptSymmetric}.
 * OHNE Netz und OHNE Secrets — CI-tauglich.
 *
 * <p>Kernaussage: {@code encryptSymmetric} erzeugt eine gueltige EncString type 2, die
 * {@code decryptSymmetric} mit demselben Schluessel verlustfrei zurueckliefert. Die
 * beiden Methoden bilden ein Paar (AES-256-CBC/PKCS7 + HMAC-SHA256 ueber IV||CT).</p>
 */
class VaultwardenEncryptSymmetricTest {

    /** Round-Trip fuer mehrere Laengen inkl. leer, Sub-Block, Blockgrenze, Multi-Block. */
    @Test
    void encryptThenDecrypt_roundTripsForManyLengths() {
        byte[] key64 = randomBytes(64);
        int[] lengths = {0, 1, 5, 15, 16, 17, 31, 32, 33, 64, 100, 255, 1024};
        for (int len : lengths) {
            byte[] plaintext = randomBytes(len);
            String encString = VaultwardenCrypto.encryptSymmetric(plaintext, key64);
            byte[] decrypted = VaultwardenCrypto.decryptSymmetric(EncString.parse(encString), key64);
            assertThat(decrypted)
                    .as("Round-Trip fuer Laenge %d", len)
                    .isEqualTo(plaintext);
        }
    }

    /** UTF-8-Passwort mit Umlauten/Emoji ueberlebt den Round-Trip 1:1. */
    @Test
    void encryptThenDecrypt_utf8Password() {
        byte[] key64 = randomBytes(64);
        String password = "n3w-P@ssw0rd-äöü-⚡-😀";
        String encString = VaultwardenCrypto.encryptSymmetric(
                password.getBytes(StandardCharsets.UTF_8), key64);
        byte[] decrypted = VaultwardenCrypto.decryptSymmetric(EncString.parse(encString), key64);
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo(password);
    }

    /** Ergebnis ist ein parsebarer EncString type 2 mit 16-Byte-IV und 32-Byte-MAC. */
    @Test
    void produced_isParseableType2() {
        byte[] key64 = randomBytes(64);
        String encString = VaultwardenCrypto.encryptSymmetric(
                "hello".getBytes(StandardCharsets.UTF_8), key64);

        assertThat(encString).startsWith("2.");
        EncString enc = EncString.parse(encString);
        assertThat(enc.type()).isEqualTo(2);
        assertThat(enc.isSymmetric()).isTrue();
        assertThat(enc.iv()).hasSize(16);
        assertThat(enc.mac()).hasSize(32);
        assertThat(enc.ct()).isNotEmpty();
    }

    /** MAC = HMAC-SHA256(macKey, IV||CT) — exakt so wie decryptSymmetric verifiziert. */
    @Test
    void producedMac_isHmacOverIvAndCt() throws Exception {
        byte[] key64 = randomBytes(64);
        String encString = VaultwardenCrypto.encryptSymmetric(
                "verify-mac".getBytes(StandardCharsets.UTF_8), key64);
        EncString enc = EncString.parse(encString);

        byte[] macKey = Arrays.copyOfRange(key64, 32, 64);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
        mac.update(enc.iv());
        mac.update(enc.ct());
        byte[] expected = mac.doFinal();

        assertThat(MessageDigest.isEqual(expected, enc.mac())).isTrue();
    }

    /** Zufaelliger IV: zweimal dasselbe Klartext-/Schluesselpaar ergibt verschiedene CTs. */
    @Test
    void ivIsRandom_ciphertextsDiffer() {
        byte[] key64 = randomBytes(64);
        byte[] plaintext = "same-plaintext".getBytes(StandardCharsets.UTF_8);
        String a = VaultwardenCrypto.encryptSymmetric(plaintext, key64);
        String b = VaultwardenCrypto.encryptSymmetric(plaintext, key64);
        assertThat(a).isNotEqualTo(b);
        // ... entschluesseln aber beide zum selben Klartext
        assertThat(VaultwardenCrypto.decryptSymmetric(EncString.parse(a), key64))
                .isEqualTo(VaultwardenCrypto.decryptSymmetric(EncString.parse(b), key64))
                .isEqualTo(plaintext);
    }

    /** Ein anderer Schluessel schlaegt die MAC-Pruefung fehl (kein stiller Datenverlust). */
    @Test
    void wrongKey_failsMacVerification() {
        byte[] key64 = randomBytes(64);
        byte[] otherKey = randomBytes(64);
        String encString = VaultwardenCrypto.encryptSymmetric(
                "secret".getBytes(StandardCharsets.UTF_8), key64);

        EncString parsed = EncString.parse(encString);
        assertThatThrownBy(() ->
                VaultwardenCrypto.decryptSymmetric(parsed, otherKey))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsShortKey() {
        assertThatThrownBy(() ->
                VaultwardenCrypto.encryptSymmetric(new byte[4], new byte[32]))
                .isInstanceOf(IllegalStateException.class);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}

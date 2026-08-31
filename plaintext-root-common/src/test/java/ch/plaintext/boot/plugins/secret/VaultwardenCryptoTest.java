/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/**
 * Deterministic crypto tests WITHOUT a network and WITHOUT secrets — CI-capable.
 *
 * <p>The expected values were computed with the Python reference ({@code hashlib.pbkdf2_hmac},
 * {@code hmac}) against which the live PoC verified the vault.</p>
 */
class VaultwardenCryptoTest {

    private static final HexFormat HEX = HexFormat.of();

    // --- PBKDF2-HMAC-SHA256 (widely published test vectors) ---

    @Test
    void pbkdf2_matchesReferenceVectors() {
        assertThat(HEX.formatHex(VaultwardenCrypto.pbkdf2Sha256(
                "password".getBytes(StandardCharsets.UTF_8),
                "salt".getBytes(StandardCharsets.UTF_8), 1, 32)))
                .isEqualTo("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b");

        assertThat(HEX.formatHex(VaultwardenCrypto.pbkdf2Sha256(
                "password".getBytes(StandardCharsets.UTF_8),
                "salt".getBytes(StandardCharsets.UTF_8), 2, 32)))
                .isEqualTo("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43");

        assertThat(HEX.formatHex(VaultwardenCrypto.pbkdf2Sha256(
                "password".getBytes(StandardCharsets.UTF_8),
                "salt".getBytes(StandardCharsets.UTF_8), 4096, 32)))
                .isEqualTo("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a");
    }

    /** Covers Bitwarden steps 2+3: masterKey -> masterPasswordHash(Base64). */
    @Test
    void masterPasswordHash_matchesPythonReference() {
        byte[] masterKey = VaultwardenCrypto.pbkdf2Sha256(
                "masterpw".getBytes(StandardCharsets.UTF_8),
                "user@example.com".getBytes(StandardCharsets.UTF_8), 600000, 32);
        assertThat(HEX.formatHex(masterKey))
                .isEqualTo("30c8668076cd385f007d5b0e8255d42311be95c23271c7751083a11eb5f3dd31");

        byte[] mph = VaultwardenCrypto.pbkdf2Sha256(
                masterKey, "masterpw".getBytes(StandardCharsets.UTF_8), 1, 32);
        assertThat(Base64.getEncoder().encodeToString(mph))
                .isEqualTo("MXfxicrV51i+sQGdRIKDQ0LE1R5EYtns2XY/iJflta8=");
    }

    // --- HKDF-Expand-SHA256 (Bitwarden stretchKey) ---

    @Test
    void hkdfExpand_matchesReferenceVectors() {
        byte[] prk = new byte[32];
        Arrays.fill(prk, (byte) 0x0b);
        assertThat(HEX.formatHex(VaultwardenCrypto.hkdfExpandSha256(prk, "enc", 32)))
                .isEqualTo("b0161a15ba451c50c1ee66d6721df26473e883417ead41c665eb6486afd116b0");
        assertThat(HEX.formatHex(VaultwardenCrypto.hkdfExpandSha256(prk, "mac", 32)))
                .isEqualTo("87d15438287e23cf482e6c118c28f81d3908b9b651323e38adf61f717924c24b");
    }

    @Test
    void stretchMasterKey_isEncConcatMac() {
        byte[] prk = new byte[32];
        Arrays.fill(prk, (byte) 0x0b);
        byte[] stretched = VaultwardenCrypto.stretchMasterKey(prk);
        assertThat(stretched).hasSize(64);
        assertThat(Arrays.copyOfRange(stretched, 0, 32))
                .isEqualTo(VaultwardenCrypto.hkdfExpandSha256(prk, "enc", 32));
        assertThat(Arrays.copyOfRange(stretched, 32, 64))
                .isEqualTo(VaultwardenCrypto.hkdfExpandSha256(prk, "mac", 32));
    }

    // --- decryptSymmetric: round trip (encrypt -> decrypt) + MAC + PKCS7 ---

    @Test
    void decryptSymmetric_roundTrip() throws Exception {
        byte[] key64 = randomBytes(64);
        String plaintext = "s3cr3t-Paperless-Token-äöü-⚡";
        String encString = encryptType2(key64, plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] decrypted = VaultwardenCrypto.decryptSymmetric(EncString.parse(encString), key64);
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo(plaintext);
    }

    @Test
    void decryptSymmetric_pkcs7FullBlockPadding() throws Exception {
        byte[] key64 = randomBytes(64);
        // length exactly 16 -> PKCS7 appends a complete 16-byte padding block
        byte[] plaintext = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String encString = encryptType2(key64, plaintext);
        byte[] decrypted = VaultwardenCrypto.decryptSymmetric(EncString.parse(encString), key64);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void decryptSymmetric_tamperedMacIsRejected() throws Exception {
        byte[] key64 = randomBytes(64);
        String encString = encryptType2(key64, "hello".getBytes(StandardCharsets.UTF_8));
        // flip the MAC part
        String[] parts = encString.substring(2).split("\\|");
        byte[] mac = Base64.getDecoder().decode(parts[2]);
        mac[0] ^= 0x01;
        String tampered = "2." + parts[0] + "|" + parts[1] + "|" + Base64.getEncoder().encodeToString(mac);

        EncString parsedTampered = EncString.parse(tampered);
        assertThatThrownBy(() -> VaultwardenCrypto.decryptSymmetric(parsedTampered, key64))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAC");
    }

    @Test
    void decryptSymmetric_wrongKeyLengthRejected() {
        EncString wrongLengthKeyInput = new EncString(2, new byte[16], new byte[16], new byte[32]);
        assertThatThrownBy(() -> VaultwardenCrypto.decryptSymmetric(wrongLengthKeyInput, new byte[32]))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- Test-side encrypt reference (mirrors Bitwarden's EncString type 2) ---

    private static String encryptType2(byte[] key64, byte[] plaintext) throws Exception {
        byte[] encKey = Arrays.copyOfRange(key64, 0, 32);
        byte[] macKey = Arrays.copyOfRange(key64, 32, 64);
        byte[] iv = randomBytes(16);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plaintext);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
        mac.update(iv);
        mac.update(ct);
        byte[] macTag = mac.doFinal();
        Base64.Encoder b64 = Base64.getEncoder();
        return "2." + b64.encodeToString(iv) + "|" + b64.encodeToString(ct) + "|" + b64.encodeToString(macTag);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}

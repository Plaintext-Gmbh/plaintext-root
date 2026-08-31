/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SECURITY (card 314, item 8) — predictable crypto fallback.
 *
 * <p>If {@code PLAINTEXT_SECRET_KEY} is missing, {@code SecretCrypto} derived the AES key from
 * {@code sha256("plaintext-dev-fallback-" + HOSTNAME)}. If {@code HOSTNAME} is not set —
 * and neither the Dockerfile nor compose.yaml sets the variable — the value is the constant string
 * "null" and the key is therefore publicly computable. In PROD this is now a startup error
 * instead of an easily overlooked WARN. The crypto itself is unchanged (AES-256-GCM,
 * fresh IV per call).
 */
@DisplayName("SecretCrypto: Dev-Fallback")
class SecretCryptoFallbackTest {

    @Test
    void failsFastInProductionWithoutKey() {
        if (System.getenv("PLAINTEXT_SECRET_KEY") != null) {
            return; // not meaningful in an environment with a real key
        }
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new SecretCrypto(prod));

        assertTrue(ex.getMessage().contains("PLAINTEXT_SECRET_KEY"));
    }

    @Test
    void usesFallbackOutsideProduction() {
        SecretCrypto crypto = new SecretCrypto(new MockEnvironment());

        String cipher = crypto.encrypt("geheim");

        assertNotEquals("geheim", cipher);
        assertEquals("geheim", crypto.decrypt(cipher));
    }

    /** No ECB: thanks to the fresh IV the same plaintext yields two different ciphertexts. */
    @Test
    void producesDifferentCiphertextForSamePlaintext() {
        SecretCrypto crypto = new SecretCrypto(new MockEnvironment());

        assertNotEquals(crypto.encrypt("geheim"), crypto.encrypt("geheim"));
    }
}

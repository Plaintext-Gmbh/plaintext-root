/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SECURITY (card 376, originally item 8 of collective card 314) — predictable crypto fallback, here
 * for the <b>second</b> affected class.
 *
 * <p>{@code WebhookCrypto} uses the same env key and the same fallback as {@code SecretCrypto}.
 * Since card 1301 both share ONE implementation
 * ({@code ch.plaintext.boot.plugins.secret.EnvKeyAesGcmCrypto} in plaintext-root-common), so a fix
 * can no longer land in only one of them. This test keeps the behaviour of this call site pinned
 * from the outside; the cross-check between the two call sites lives in
 * {@code KryptoHaertungVertragTest} (plaintext-root-webapp — the only module that sees both).
 *
 * <p>The case here is particularly delicate: a computable key yields readable signing secrets and
 * from those <b>valid signatures</b> for outgoing webhooks.
 */
@DisplayName("WebhookCrypto: Dev-Fallback")
class WebhookCryptoFallbackTest {

    @Test
    void failsFastInProductionWithoutKey() {
        if (System.getenv("PLAINTEXT_SECRET_KEY") != null) {
            return; // not meaningful in an environment with a real key
        }
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new WebhookCrypto(prod));

        assertTrue(ex.getMessage().contains("PLAINTEXT_SECRET_KEY"));
    }

    @Test
    void usesFallbackOutsideProduction() {
        WebhookCrypto crypto = new WebhookCrypto(new MockEnvironment());

        String cipher = crypto.encrypt("signing-secret");

        assertNotEquals("signing-secret", cipher);
        assertEquals("signing-secret", crypto.decrypt(cipher));
    }

    /**
     * The drift found in card 1301: {@code SecretCrypto} could report the publicly computable dev
     * key, this class could not. The merged implementation is the stronger one.
     */
    @Test
    void meldetDenDevFallback() {
        if (System.getenv("PLAINTEXT_SECRET_KEY") != null) {
            return; // not meaningful in an environment with a real key
        }
        assertTrue(new WebhookCrypto(new MockEnvironment()).isDevFallback());
    }

    /** No ECB: thanks to the fresh IV, the same plain text yields two different ciphertexts. */
    @Test
    void producesDifferentCiphertextForSamePlaintext() {
        WebhookCrypto crypto = new WebhookCrypto(new MockEnvironment());

        assertNotEquals(crypto.encrypt("signing-secret"), crypto.encrypt("signing-secret"));
    }
}

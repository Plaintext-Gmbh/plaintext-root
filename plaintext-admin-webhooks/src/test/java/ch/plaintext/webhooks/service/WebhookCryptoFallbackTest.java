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
 * <p>{@code WebhookCrypto} uses the same env key and the same fallback as {@code SecretCrypto}, but
 * is deliberately duplicated (no module references {@code plaintext-admin-secrets} across module
 * boundaries). A fix in only one of the two classes would leave the hole open — which is why this
 * test exists as the counterpart to {@code SecretCryptoFallbackTest}.
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

    /** No ECB: thanks to the fresh IV, the same plain text yields two different ciphertexts. */
    @Test
    void producesDifferentCiphertextForSamePlaintext() {
        WebhookCrypto crypto = new WebhookCrypto(new MockEnvironment());

        assertNotEquals(crypto.encrypt("signing-secret"), crypto.encrypt("signing-secret"));
    }
}

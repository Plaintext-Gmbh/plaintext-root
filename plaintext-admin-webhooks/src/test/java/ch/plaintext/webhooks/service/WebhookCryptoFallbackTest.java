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
 * SECURITY (Karte 376, urspruenglich Punkt 8 der Sammelkarte 314) — vorhersagbarer Krypto-Fallback,
 * hier fuer die <b>zweite</b> betroffene Klasse.
 *
 * <p>{@code WebhookCrypto} benutzt denselben Env-Key und denselben Fallback wie
 * {@code SecretCrypto}, ist aber bewusst dupliziert (kein Modul referenziert quer auf
 * {@code plaintext-admin-secrets}). Ein Fix in nur einer der beiden Klassen liesse die Luecke
 * offen — deshalb existiert dieser Test als Gegenstueck zu {@code SecretCryptoFallbackTest}.
 *
 * <p>Besonders heikel ist der Fall hier: Aus einem berechenbaren Schluessel folgen lesbare
 * Signing-Secrets und daraus <b>gueltige Signaturen</b> fuer ausgehende Webhooks.
 */
@DisplayName("WebhookCrypto: Dev-Fallback")
class WebhookCryptoFallbackTest {

    @Test
    void failsFastInProductionWithoutKey() {
        if (System.getenv("PLAINTEXT_SECRET_KEY") != null) {
            return; // in einer Umgebung mit echtem Key nicht aussagekraeftig
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

    /** Kein ECB: derselbe Klartext ergibt durch den frischen IV zwei verschiedene Chiffrate. */
    @Test
    void producesDifferentCiphertextForSamePlaintext() {
        WebhookCrypto crypto = new WebhookCrypto(new MockEnvironment());

        assertNotEquals(crypto.encrypt("signing-secret"), crypto.encrypt("signing-secret"));
    }
}

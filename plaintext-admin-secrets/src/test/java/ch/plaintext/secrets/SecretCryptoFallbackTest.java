/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SECURITY (Karte 314, Punkt 8) — vorhersagbarer Krypto-Fallback.
 *
 * <p>Fehlt {@code PLAINTEXT_SECRET_KEY}, leitete {@code SecretCrypto} den AES-Schluessel aus
 * {@code sha256("plaintext-dev-fallback-" + HOSTNAME)} ab. Ist {@code HOSTNAME} nicht gesetzt —
 * und weder Dockerfile noch compose.yaml setzen die Variable — ist der Wert der konstante String
 * "null" und der Schluessel damit oeffentlich berechenbar. In PROD ist das jetzt ein Startfehler
 * statt einer leicht zu uebersehenden WARN. Die Krypto selbst ist unveraendert (AES-256-GCM,
 * frischer IV je Aufruf).
 */
@DisplayName("SecretCrypto: Dev-Fallback")
class SecretCryptoFallbackTest {

    @Test
    void failsFastInProductionWithoutKey() {
        if (System.getenv("PLAINTEXT_SECRET_KEY") != null) {
            return; // in einer Umgebung mit echtem Key nicht aussagekraeftig
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

    /** Kein ECB: derselbe Klartext ergibt durch den frischen IV zwei verschiedene Chiffrate. */
    @Test
    void producesDifferentCiphertextForSamePlaintext() {
        SecretCrypto crypto = new SecretCrypto(new MockEnvironment());

        assertNotEquals(crypto.encrypt("geheim"), crypto.encrypt("geheim"));
    }
}

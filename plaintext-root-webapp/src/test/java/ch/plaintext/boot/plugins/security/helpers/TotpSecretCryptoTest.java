/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.helpers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TOTP secret at rest: encrypted with a key, legacy values in plain text stay readable. */
class TotpSecretCryptoTest {

    private final TotpSecretConverter converter = new TotpSecretConverter();

    @AfterEach
    void tearDown() {
        TotpSecretCrypto.reset();
    }

    @Test
    @DisplayName("Mit Schluessel: Roundtrip, Praefix, jedes Mal anderer IV")
    void roundtrip() {
        TotpSecretCrypto.configure("test-schluessel");
        String a = converter.convertToDatabaseColumn("JBSWY3DPEHPK3PXP");
        String b = converter.convertToDatabaseColumn("JBSWY3DPEHPK3PXP");
        assertTrue(a.startsWith("enc1:"));
        assertNotEquals(a, b, "IV muss zufaellig sein");
        assertEquals("JBSWY3DPEHPK3PXP", converter.convertToEntityAttribute(a));
        assertEquals("JBSWY3DPEHPK3PXP", converter.convertToEntityAttribute(b));
        assertTrue(a.length() <= 255, "muss in VARCHAR(255) passen: " + a.length());
    }

    @Test
    @DisplayName("Altbestand im Klartext wird unveraendert gelesen — auch mit Schluessel")
    void klartextAltbestand() {
        TotpSecretCrypto.configure("test-schluessel");
        assertEquals("JBSWY3DPEHPK3PXP", converter.convertToEntityAttribute("JBSWY3DPEHPK3PXP"));
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(converter.convertToDatabaseColumn(null));
    }

    @Test
    @DisplayName("Ohne Schluessel: Klartext schreiben (dev), verschluesselte Werte sind ein Fehler")
    void ohneSchluessel() {
        TotpSecretCrypto.configure("");
        assertFalse(TotpSecretCrypto.isConfigured());
        assertEquals("JBSWY3DPEHPK3PXP", converter.convertToDatabaseColumn("JBSWY3DPEHPK3PXP"));
        assertThrows(IllegalStateException.class, () -> converter.convertToEntityAttribute("enc1:AAAA"));
    }

    @Test
    @DisplayName("Falscher Schluessel: klarer Fehler statt Muell")
    void falscherSchluessel() {
        TotpSecretCrypto.configure("schluessel-a");
        String enc = converter.convertToDatabaseColumn("JBSWY3DPEHPK3PXP");
        TotpSecretCrypto.configure("schluessel-b");
        assertThrows(IllegalStateException.class, () -> converter.convertToEntityAttribute(enc));
    }
}

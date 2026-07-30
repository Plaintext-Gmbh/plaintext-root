/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rechnungen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvoicePDFOptionsTest {

    @Test
    void validConstruction_setsAllFields() {
        byte[] image = new byte[]{1, 2, 3};

        InvoicePDFOptions options = new InvoicePDFOptions(
                "Firma AG",
                "Hauptstrasse 1",
                "8000",
                "Zuerich",
                "Kunde GmbH",
                "Nebenstrasse 2",
                "3000",
                "Bern",
                "CH12 3456 7890 1234 5678 9",
                "Firma AG",
                image,
                "image/png",
                false,
                "https://example.com/logo.png"
        );

        assertEquals("Firma AG", options.absenderName());
        assertEquals("Hauptstrasse 1", options.absenderStrasse());
        assertEquals("8000", options.absenderPlz());
        assertEquals("Zuerich", options.absenderOrt());
        assertEquals("Kunde GmbH", options.empfaengerName());
        assertEquals("Nebenstrasse 2", options.empfaengerStrasse());
        assertEquals("3000", options.empfaengerPlz());
        assertEquals("Bern", options.empfaengerOrt());
        assertEquals("CH12 3456 7890 1234 5678 9", options.creditorIban());
        assertEquals("Firma AG", options.creditorName());
        assertArrayEquals(image, options.belegImage());
        assertEquals("image/png", options.belegContentType());
        assertFalse(options.ohneMwst());
        assertEquals("https://example.com/logo.png", options.logoUrl());
    }

    @Test
    void nullAbsenderName_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new InvoicePDFOptions(
                        null, "street", "zip", "city",
                        "emp", "street", "zip", "city",
                        "iban", "name", null, null,
                        false, null
                )
        );
    }

    @Test
    void blankAbsenderName_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new InvoicePDFOptions(
                        "   ", "street", "zip", "city",
                        "emp", "street", "zip", "city",
                        "iban", "name", null, null,
                        false, null
                )
        );
    }

    @Test
    void emptyAbsenderName_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new InvoicePDFOptions(
                        "", "street", "zip", "city",
                        "emp", "street", "zip", "city",
                        "iban", "name", null, null,
                        false, null
                )
        );
    }

    @Test
    void validAbsenderName_noException() {
        assertDoesNotThrow(() ->
                new InvoicePDFOptions(
                        "Valid Name", null, null, null,
                        null, null, null, null,
                        null, null, null, null,
                        true, null
                )
        );
    }

    @Test
    void equalsAndHashCode_sameValues() {
        InvoicePDFOptions opt1 = new InvoicePDFOptions(
                "A", "s", "z", "c", "e", "s", "z", "c",
                "iban", "n", null, null, false, null
        );
        InvoicePDFOptions opt2 = new InvoicePDFOptions(
                "A", "s", "z", "c", "e", "s", "z", "c",
                "iban", "n", null, null, false, null
        );

        assertEquals(opt1, opt2);
        assertEquals(opt1.hashCode(), opt2.hashCode());
    }

    @Test
    void toString_containsFieldValues() {
        InvoicePDFOptions options = new InvoicePDFOptions(
                "Firma AG", null, null, null,
                null, null, null, null,
                null, null, null, null,
                false, null
        );

        String str = options.toString();
        assertTrue(str.contains("Firma AG"));
    }
}

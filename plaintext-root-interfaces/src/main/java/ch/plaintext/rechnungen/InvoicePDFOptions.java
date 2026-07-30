/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rechnungen;

/**
 * Options for customized invoice PDF generation.
 * Used by the Auszahlungen module to generate invoices with custom sender, recipient, IBAN and attachment.
 *
 * @param absenderName      the sender's name (required)
 * @param absenderStrasse   the sender's street address
 * @param absenderPlz       the sender's postal code
 * @param absenderOrt       the sender's city
 * @param empfaengerName    the recipient's name
 * @param empfaengerStrasse the recipient's street address
 * @param empfaengerPlz     the recipient's postal code
 * @param empfaengerOrt     the recipient's city
 * @param creditorIban      the creditor IBAN for the QR bill payment slip
 * @param creditorName      the creditor name for the QR bill payment slip
 * @param belegImage        optional receipt/document image as byte array
 * @param belegContentType  the MIME content type of the receipt image (e.g. "image/png")
 * @param ohneMwst          whether to exclude VAT from the invoice
 * @param logoUrl           optional URL for a custom logo on the invoice
 */
public record InvoicePDFOptions(
        String absenderName,
        String absenderStrasse,
        String absenderPlz,
        String absenderOrt,
        String empfaengerName,
        String empfaengerStrasse,
        String empfaengerPlz,
        String empfaengerOrt,
        String creditorIban,
        String creditorName,
        byte[] belegImage,
        String belegContentType,
        boolean ohneMwst,
        String logoUrl
) {
    public InvoicePDFOptions {
        if (absenderName == null || absenderName.isBlank()) {
            throw new IllegalArgumentException("absenderName is required");
        }
    }
}

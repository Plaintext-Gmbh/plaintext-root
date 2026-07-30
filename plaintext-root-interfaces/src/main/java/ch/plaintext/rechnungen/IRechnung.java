/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rechnungen;

import java.util.Date;

/**
 * Interface representing an invoice (Rechnung).
 */
public interface IRechnung {

    /**
     * Gets the unique identifier of the invoice.
     *
     * @return the invoice ID
     */
    Long getId();

    /**
     * Gets the invoice number (e.g. "R-2026-001").
     *
     * @return the invoice number
     */
    String getRechnungsnummer();

    /**
     * Gets the contact ID of the invoice recipient.
     *
     * @return the contact ID
     */
    Long getKontaktId();

    /**
     * Gets the subject line of the invoice.
     *
     * @return the subject
     */
    String getBetreff();

    /**
     * Gets the hourly rate in CHF.
     *
     * @return the price per hour
     */
    double getPreisProStunde();

    /**
     * Gets the total number of hours billed.
     *
     * @return the total hours
     */
    double getGesamtStunden();

    /**
     * Gets the total amount of the invoice in CHF.
     *
     * @return the total amount
     */
    double getGesamtbetrag();

    /**
     * Gets the invoice date.
     *
     * @return the invoice date
     */
    Date getRechnungsdatum();

    /**
     * Gets the billing period description (e.g. "Januar 2026").
     *
     * @return the billing period
     */
    String getZeitraum();

    /**
     * Gets the QR reference (QRR, 27 digits) used for payment reconciliation.
     *
     * @return the QR reference, or null if not set
     */
    String getQrReferenz();

    /**
     * Gets the name of the invoice status (e.g. "OFFEN", "BEZAHLT").
     *
     * @return the status name, or null if no status is set
     */
    String getStatusName();
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.kontakte;

/**
 * Interface for contact entities.
 * This interface allows different modules to work with contact data without
 * direct dependency on the concrete Kontakt entity.
 */
public interface IKontakt {

    /**
     * Get the unique identifier of the contact.
     * @return the contact ID
     */
    Long getId();

    /**
     * Get the display name of the contact.
     * This should return the full name or company name as it should be displayed.
     * @return the display name
     */
    String getDisplayName();

    /**
     * Get the first name (Vorname).
     * @return the first name, or null if not applicable
     */
    String getVorname();

    /**
     * Get the last name (Nachname).
     * @return the last name, or null if not applicable
     */
    String getNachname();

    /**
     * Get the company name (Firma).
     * @return the company name, or null if not applicable
     */
    String getFirma();

    /**
     * Get the street address (Strasse).
     * @return the street address
     */
    String getStrasse();

    /**
     * Get the postal code (PLZ).
     * @return the postal code
     */
    String getPlz();

    /**
     * Get the city (Ort).
     * @return the city
     */
    String getOrt();

    /**
     * Get the country (Land).
     * @return the country, or null if not set
     */
    String getLand();

    /**
     * Get the email address.
     * @return the email address, or null if not set
     */
    String getEmail();

    /**
     * Get the phone number.
     * @return the phone number, or null if not set
     */
    String getTelefon();

    /**
     * Get the bank account number (IBAN / Postkonto).
     * @return the account number, or null if not set
     */
    default String getKonto() {
        return null;
    }
}

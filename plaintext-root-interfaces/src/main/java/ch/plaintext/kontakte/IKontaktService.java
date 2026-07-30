/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.kontakte;

import java.util.List;
import java.util.Optional;

/**
 * Service interface for managing contacts.
 * This interface allows modules to access contact data without direct dependency
 * on the implementation. If no implementation is available, modules can fall back
 * to their own local contact management.
 */
public interface IKontaktService {

    /**
     * Find all contacts for the current user/tenant.
     * @return list of all contacts
     */
    List<IKontakt> findAll();

    /**
     * Find all contacts for a specific Mandat.
     * @param mandatId the Mandat ID to filter by
     * @return list of contacts for the given Mandat
     */
    List<IKontakt> findByMandat(Long mandatId);

    /**
     * Find a contact by its ID.
     * @param id the contact ID
     * @return Optional containing the contact if found, empty otherwise
     */
    Optional<IKontakt> findById(Long id);

    /**
     * Search contacts by name (searches in first name, last name, and company name).
     * @param searchTerm the search term
     * @return list of matching contacts
     */
    List<IKontakt> searchByName(String searchTerm);

    /**
     * Check if the KontaktService is available and configured.
     * Modules can use this to determine whether to use the service or fall back
     * to local contact management.
     * @return true if the service is available and ready to use
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Find a contact by email address within the current mandat.
     * @param email the email address to search for
     * @return Optional containing the contact if found
     */
    default Optional<IKontakt> findByEmail(String email) {
        return Optional.empty();
    }

    /**
     * Update an existing contact's fields.
     * @param id the contact ID
     * @param vorname first name
     * @param nachname last name
     * @param firma company name
     * @param strasse street
     * @param plz postal code
     * @param ort city
     * @param email email
     * @param tel phone
     * @param konto bank account (IBAN)
     * @return the updated contact, or empty if not found
     */
    default Optional<IKontakt> updateKontakt(Long id, String vorname, String nachname, String firma,
                                              String strasse, String plz, String ort,
                                              String email, String tel, String konto) {
        return Optional.empty();
    }

    /**
     * Create a new contact in the current mandat.
     * @return the created contact
     */
    default IKontakt createKontakt(String vorname, String nachname, String firma,
                                    String strasse, String plz, String ort,
                                    String email, String tel, String konto) {
        throw new UnsupportedOperationException("createKontakt not supported");
    }

    /**
     * Wie {@link #updateKontakt} mit zusaetzlicher zweiter E-Mail ({@code email2}). Default delegiert an
     * die Variante ohne email2 (email2 wird dann ignoriert) — die Impl ueberschreibt fuer echtes Setzen.
     */
    default Optional<IKontakt> updateKontakt(Long id, String vorname, String nachname, String firma,
                                              String strasse, String plz, String ort,
                                              String email, String email2, String tel, String konto) {
        return updateKontakt(id, vorname, nachname, firma, strasse, plz, ort, email, tel, konto);
    }

    /**
     * Wie {@link #createKontakt} mit zusaetzlicher zweiter E-Mail ({@code email2}). Default delegiert an
     * die Variante ohne email2 (email2 wird dann ignoriert) — die Impl ueberschreibt fuer echtes Setzen.
     */
    default IKontakt createKontakt(String vorname, String nachname, String firma,
                                    String strasse, String plz, String ort,
                                    String email, String email2, String tel, String konto) {
        return createKontakt(vorname, nachname, firma, strasse, plz, ort, email, tel, konto);
    }
}

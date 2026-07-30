/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rechnungen;

/**
 * Interface for a contact option used in dropdown selections.
 */
public interface IKontaktOption {

    /**
     * Gets the unique identifier of the contact.
     *
     * @return the contact ID
     */
    Long getId();

    /**
     * Gets the display name of the contact (e.g. "Max Mustermann - Firma AG").
     *
     * @return the display name
     */
    String getDisplayName();
}

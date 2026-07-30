/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

/**
 * Ein zusätzlicher Mandant, der einem Benutzer zugeordnet ist. Der Heimat-Mandant
 * steckt weiterhin in der {@code PROPERTY_MANDAT_}-Rolle des Benutzers; diese Entität
 * listet die WEITEREN Mandate, zwischen denen der Benutzer wechseln darf.
 *
 * @author mad
 * @since 2026
 */
@Entity
@Data
public class UserMandate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Login-Name des Benutzers (kein FK, analog zu {@code ROLLENZUTEILUNG}). */
    private String username;

    /** Zugeordneter (zusätzlicher) Mandant, kleingeschrieben gespeichert. */
    private String mandat;

    /** Ob die Zuordnung aktiv ist. */
    private boolean active = true;
}

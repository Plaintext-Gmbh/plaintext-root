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
 * An additional tenant assigned to a user. The home tenant
 * still sits in the {@code PROPERTY_MANDAT_} role of the user; this entity
 * lists the FURTHER tenants the user is allowed to switch between.
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

    /** Login name of the user (no FK, analogously to {@code ROLLENZUTEILUNG}). */
    private String username;

    /** Assigned (additional) tenant, stored in lower case. */
    private String mandat;

    /** Whether the assignment is active. */
    private boolean active = true;
}

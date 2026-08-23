/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import java.io.Serializable;
import java.util.Locale;

/**
 * Eine von einem Modul deklarierte Rolle: technischer Name plus menschenlesbare Beschreibung.
 *
 * <p>Module deklarieren ihre Rollen ueber {@link PlaintextRoleProvider#getDeclaredRoles()};
 * die {@link PlaintextRoleRegistry} sammelt alle Deklarationen ein und stellt sie u.a. der
 * Benutzerverwaltung als Auswahl zur Verfuegung.</p>
 *
 * <p><b>Namens-Konvention:</b> {@link #name()} ist der technische Rollenname OHNE
 * {@code ROLE_}-Prefix (z.B. {@code admin}). Fuer die Identitaet (Dedup in der Registry)
 * zaehlt {@link #normalizedName()}: lowercase und ohne {@code ROLE_}-Prefix — so wie die
 * Rollen auch am {@code MyUserEntity} gespeichert werden. {@link #authorityName()} liefert
 * die Spring-Security-Schreibweise {@code ROLE_<UPPERCASE>}, wie sie der
 * {@code MyUserDetailsService} beim Login vergibt.</p>
 *
 * @param name        technischer Rollenname (mit oder ohne {@code ROLE_}-Prefix deklariert)
 * @param description menschenlesbare Beschreibung fuer Auswahl-UIs; nie {@code null} (ggf. leer)
 * @author info@plaintext.ch
 * @since 1.600.0
 */
public record PlaintextRole(String name, String description) implements Serializable {

    /** Spring-Security-Prefix der Authority-Schreibweise. */
    private static final String ROLE_PREFIX = "ROLE_";

    public PlaintextRole {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Rollenname darf nicht leer sein");
        }
        name = name.trim();
        description = description == null ? "" : description.trim();
    }

    /**
     * Rolle ohne Beschreibung — fuer Provider, die nur {@link PlaintextRoleProvider#getRoles()}
     * implementieren (Rueckwaertskompatibilitaet).
     *
     * @param name technischer Rollenname
     * @return Rolle mit leerer Beschreibung
     */
    public static PlaintextRole of(String name) {
        return new PlaintextRole(name, "");
    }

    /**
     * Kanonische Identitaet der Rolle: lowercase, ohne {@code ROLE_}-Prefix.
     * Entspricht der Speicher-Konvention der Benutzerverwaltung.
     *
     * @return normalisierter Rollenname, z.B. {@code admin}
     */
    public String normalizedName() {
        String n = name;
        if (n.toUpperCase(Locale.ROOT).startsWith(ROLE_PREFIX)) {
            n = n.substring(ROLE_PREFIX.length());
        }
        return n.toLowerCase(Locale.ROOT);
    }

    /**
     * Spring-Security-Authority-Schreibweise der Rolle.
     *
     * @return Authority-Name, z.B. {@code ROLE_ADMIN}
     */
    public String authorityName() {
        return ROLE_PREFIX + normalizedName().toUpperCase(Locale.ROOT);
    }
}

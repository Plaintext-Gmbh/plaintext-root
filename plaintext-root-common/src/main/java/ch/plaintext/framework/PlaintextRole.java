/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import java.io.Serializable;
import java.util.Locale;

/**
 * A role declared by a module: technical name plus human-readable description.
 *
 * <p>Modules declare their roles through {@link PlaintextRoleProvider#getDeclaredRoles()};
 * the {@link PlaintextRoleRegistry} collects all declarations and offers them, among other
 * things, to the user administration as a selection.</p>
 *
 * <p><b>Naming convention:</b> {@link #name()} is the technical role name WITHOUT the
 * {@code ROLE_} prefix (e.g. {@code admin}). What counts for identity (dedup in the registry)
 * is {@link #normalizedName()}: lowercase and without the {@code ROLE_} prefix — the same way
 * the roles are stored on the {@code MyUserEntity}. {@link #authorityName()} returns the
 * Spring Security spelling {@code ROLE_<UPPERCASE>}, the way the
 * {@code MyUserDetailsService} assigns it at login.</p>
 *
 * @param name        technical role name (declared with or without the {@code ROLE_} prefix)
 * @param description human-readable description for selection UIs; never {@code null} (possibly empty)
 * @author info@plaintext.ch
 * @since 1.600.0
 */
public record PlaintextRole(String name, String description) implements Serializable {

    /** Spring Security prefix of the authority spelling. */
    private static final String ROLE_PREFIX = "ROLE_";

    public PlaintextRole {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Rollenname darf nicht leer sein");
        }
        name = name.trim();
        description = description == null ? "" : description.trim();
    }

    /**
     * Role without a description — for providers that only implement
     * {@link PlaintextRoleProvider#getRoles()} (backwards compatibility).
     *
     * @param name technical role name
     * @return role with an empty description
     */
    public static PlaintextRole of(String name) {
        return new PlaintextRole(name, "");
    }

    /**
     * Canonical identity of the role: lowercase, without the {@code ROLE_} prefix.
     * Matches the storage convention of the user administration.
     *
     * @return normalized role name, e.g. {@code admin}
     */
    public String normalizedName() {
        String n = name;
        if (n.toUpperCase(Locale.ROOT).startsWith(ROLE_PREFIX)) {
            n = n.substring(ROLE_PREFIX.length());
        }
        return n.toLowerCase(Locale.ROOT);
    }

    /**
     * Spring Security authority spelling of the role.
     *
     * @return authority name, e.g. {@code ROLE_ADMIN}
     */
    public String authorityName() {
        return ROLE_PREFIX + normalizedName().toUpperCase(Locale.ROOT);
    }
}

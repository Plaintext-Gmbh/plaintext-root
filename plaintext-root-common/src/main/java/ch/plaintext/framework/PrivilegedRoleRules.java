/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import java.util.Locale;
import java.util.Set;

/**
 * Which roles only {@code root} may assign — and which ones expressly {@code admin} as well.
 *
 * <p><b>The responsibility rule.</b> <i>root</i> answers which modules belong to a tenant (the
 * tenant white/blacklist of the menu control). <i>admin</i> answers who may use them — and for
 * that admin needs the module roles. A module role
 * ({@code plaintext.menu.module-roles.<modul>=<rolle>}) grants nothing beyond access to a
 * business module; it is therefore <b>not a privileged role</b> and can be assigned by admin.</p>
 *
 * <p><b>Privileged</b> — and therefore reserved for root — is:</p>
 * <ul>
 *   <li>{@code root} and {@code admin}: they pass administrative rights on. An admin who were
 *       allowed to assign {@code admin} or {@code root} could lift their own restriction — the
 *       separation would be nothing but decoration.</li>
 *   <li>every {@code PROPERTY_*} role: it controls side entrances such as switching the tenant
 *       and therefore takes effect beyond the own tenant.</li>
 * </ul>
 *
 * <p><b>Existing assignments stay untouched.</b> The rule applies to <i>newly assigning</i> a
 * role. An assignment that is already stored remains in place and remains editable; the calling
 * places therefore check against the persisted state, not against the form.</p>
 *
 * @author info@plaintext.ch
 * @since 1.608.0
 */
public final class PrivilegedRoleRules {

    /** Roles only root may assign (normalized: lowercase, without the {@code ROLE_} prefix). */
    private static final Set<String> NUR_ROOT = Set.of("root", "admin");

    /** Prefix of the roles that take effect beyond the own tenant. */
    private static final String QUERZUGRIFF_PREFIX = "property_";

    private PrivilegedRoleRules() {
    }

    /**
     * May this role be assigned by root only?
     *
     * @param roleName role name in any spelling, with or without the {@code ROLE_} prefix
     * @return {@code true} when only root may newly assign it
     */
    public static boolean isPrivileged(String roleName) {
        String normalized = normalize(roleName);
        if (normalized.isEmpty()) {
            return false;
        }
        return NUR_ROOT.contains(normalized) || normalized.startsWith(QUERZUGRIFF_PREFIX);
    }

    /**
     * The message with which a rejected assignment is explained.
     *
     * @param roleName the rejected role
     * @return plain text for the UI
     */
    public static String rejectionMessage(String roleName) {
        return "Nur ROOT darf die Rolle '" + roleName + "' vergeben. "
                + "Modul-Rollen (Zugang zu einem Fachmodul) darf ADMIN vergeben.";
    }

    /**
     * Normalized form of a role name: trimmed, lowercase, without the {@code ROLE_} prefix.
     *
     * @param roleName raw role name, may be {@code null}
     * @return normalized name, never {@code null}
     */
    private static String normalize(String roleName) {
        if (roleName == null) {
            return "";
        }
        String value = roleName.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("role_")) {
            value = value.substring("role_".length());
        }
        return value;
    }
}

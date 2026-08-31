/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext;

/**
 * Role checks with a single vocabulary (measure 9, 29.08.2026).
 *
 * <p>The applications asked the same question in six different ways: {@code ifGranted("ROOT")},
 * {@code "ROLE_ROOT"}, {@code "ROLE_root"}, {@code "ADMIN"}, {@code "ROLE_ADMIN"},
 * {@code "ROLE_admin"} — and every second backing bean had an {@code isAdmin()} of its own.
 * {@link PlaintextSecurity#ifGranted(String)} does normalize prefix and case, but the reader
 * cannot see that. Here it is written down once.</p>
 *
 * <p>All methods are null-safe: without security (tests, background jobs) nothing is granted.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class PlaintextRoles {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_ROOT = "ROLE_ROOT";

    private PlaintextRoles() {
    }

    /** The instance role root only. */
    public static boolean isRoot(PlaintextSecurity security) {
        return security != null && security.ifGranted(ROLE_ROOT);
    }

    /** Tenant admin OR root — what almost every bean means by "admin". */
    public static boolean isAdmin(PlaintextSecurity security) {
        return security != null && (security.ifGranted(ROLE_ADMIN) || security.ifGranted(ROLE_ROOT));
    }

    /** Tenant admin only, without root. */
    public static boolean isAdminOnly(PlaintextSecurity security) {
        return security != null && security.ifGranted(ROLE_ADMIN);
    }

    /** Any of the given roles (the ROLE_ prefix is optional, case-insensitive). */
    public static boolean hasAny(PlaintextSecurity security, String... rollen) {
        if (security == null || rollen == null) {
            return false;
        }
        for (String r : rollen) {
            if (r != null && security.ifGranted(r)) {
                return true;
            }
        }
        return false;
    }
}

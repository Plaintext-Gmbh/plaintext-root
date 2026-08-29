/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext;

/**
 * Rollen-Pruefungen mit einem einzigen Vokabular (Massnahme 9, 29.08.2026).
 *
 * <p>Die Apps fragten dieselbe Sache auf sechs Arten ab: {@code ifGranted("ROOT")},
 * {@code "ROLE_ROOT"}, {@code "ROLE_root"}, {@code "ADMIN"}, {@code "ROLE_ADMIN"},
 * {@code "ROLE_admin"} — und jede zweite Backing Bean hatte ein eigenes {@code isAdmin()}.
 * {@link PlaintextSecurity#ifGranted(String)} normalisiert zwar Praefix und Schreibweise, aber
 * der Leser sieht das nicht. Hier steht es einmal.</p>
 *
 * <p>Alle Methoden sind null-sicher: ohne Security (Tests, Hintergrund-Jobs) ist nichts gewaehrt.</p>
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

    /** Nur die Instanz-Rolle root. */
    public static boolean isRoot(PlaintextSecurity security) {
        return security != null && security.ifGranted(ROLE_ROOT);
    }

    /** Mandanten-Admin ODER root — das, was fast jede Bean als „Admin" meint. */
    public static boolean isAdmin(PlaintextSecurity security) {
        return security != null && (security.ifGranted(ROLE_ADMIN) || security.ifGranted(ROLE_ROOT));
    }

    /** Nur Mandanten-Admin, ohne root. */
    public static boolean isAdminOnly(PlaintextSecurity security) {
        return security != null && security.ifGranted(ROLE_ADMIN);
    }

    /** Irgendeine der genannten Rollen (Praefix ROLE_ optional, Gross-/Kleinschreibung egal). */
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

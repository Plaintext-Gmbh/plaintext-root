/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die Trennung der Zustaendigkeiten in Rollen-Form: root vergibt Verwaltungsrechte, admin vergibt
 * Modul-Zugaenge.
 */
@DisplayName("Privilegierte Rollen")
class PrivilegedRoleRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"root", "ROOT", "Root", "ROLE_ROOT", "role_root", " root "})
    @DisplayName("root ist in jeder Schreibweise privilegiert")
    void rootIstPrivilegiert(String rolle) {
        assertTrue(PrivilegedRoleRules.isPrivileged(rolle));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "ADMIN", "ROLE_ADMIN", "role_Admin"})
    @DisplayName("admin ist privilegiert — sonst koennte admin sich selbst befoerdern")
    void adminIstPrivilegiert(String rolle) {
        assertTrue(PrivilegedRoleRules.isPrivileged(rolle));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROPERTY_MANDAT_BUTSCHER", "property_mandat_x", "ROLE_PROPERTY_X"})
    @DisplayName("PROPERTY_* wirkt ueber den eigenen Mandanten hinaus und ist privilegiert")
    void propertyRollenSindPrivilegiert(String rolle) {
        assertTrue(PrivilegedRoleRules.isPrivileged(rolle));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wiki", "ROLE_WIKI", "finanzen", "postkonto", "user", "ROLE_USER",
            "mail", "privatausgaben", "MENU_CRON"})
    @DisplayName("Modul-Rollen sind NICHT privilegiert — sie darf admin vergeben")
    void modulRollenSindNichtPrivilegiert(String rolle) {
        assertFalse(PrivilegedRoleRules.isPrivileged(rolle),
                "Modul-Rollen verleihen nur Zugang zu einem Fachmodul");
    }

    @Test
    @DisplayName("Leere Eingaben sind nicht privilegiert")
    void leereEingaben() {
        assertFalse(PrivilegedRoleRules.isPrivileged(null));
        assertFalse(PrivilegedRoleRules.isPrivileged(""));
        assertFalse(PrivilegedRoleRules.isPrivileged("   "));
        assertFalse(PrivilegedRoleRules.isPrivileged("ROLE_"));
    }

    @Test
    @DisplayName("Die Ablehnung nennt die Rolle und den Grund")
    void ablehnungsMeldung() {
        String meldung = PrivilegedRoleRules.rejectionMessage("ROLE_ADMIN");

        assertTrue(meldung.contains("ROLE_ADMIN"));
        assertTrue(meldung.contains("ROOT"));
        assertTrue(meldung.contains("Modul-Rollen"));
    }
}

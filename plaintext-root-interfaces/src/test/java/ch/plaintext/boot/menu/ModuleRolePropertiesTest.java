/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canonicalization of the module role configuration. */
class ModuleRolePropertiesTest {

    private static ModuleRoleProperties mit(Map<String, String> werte) {
        ModuleRoleProperties properties = new ModuleRoleProperties();
        properties.setModuleRoles(new LinkedHashMap<>(werte));
        return properties;
    }

    @Test
    void leereKonfigurationIstLeer() {
        assertTrue(new ModuleRoleProperties().isEmpty());
        assertTrue(new ModuleRoleProperties().canonicalModuleRoles().isEmpty());
    }

    @Test
    void nullMapWirdAufLeereMapAbgebildet() {
        ModuleRoleProperties properties = new ModuleRoleProperties();
        properties.setModuleRoles(null);

        assertTrue(properties.isEmpty());
    }

    @Test
    void keysWerdenKleinRollenGrossUndOhnePrefix() {
        Map<String, String> kanonisch = mit(Map.of(" Wiki ", " role_Wiki ")).canonicalModuleRoles();

        assertEquals(Map.of("wiki", "WIKI"), kanonisch);
    }

    @Test
    void leereEintraegeWerdenVerworfen() {
        Map<String, String> werte = new LinkedHashMap<>();
        werte.put("wiki", "");
        werte.put("  ", "finanzen");
        werte.put("mailbox", "mail");

        assertEquals(Map.of("mailbox", "MAIL"), mit(werte).canonicalModuleRoles());
        assertFalse(mit(werte).isEmpty());
    }

    @Test
    void mehrereModuleDuerfenDieselbeRolleTeilen() {
        Map<String, String> kanonisch =
                mit(Map.of("rechnungen", "finanzen", "buchhaltung", "FINANZEN")).canonicalModuleRoles();

        assertEquals(Map.of("rechnungen", "FINANZEN", "buchhaltung", "FINANZEN"), kanonisch);
    }
}

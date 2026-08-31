/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.framework.PlaintextRole;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the reference implementation of the role registry pattern: root declares its
 * own roles itself.
 */
class RootRoleProviderTest {

    private final RootRoleProvider provider = new RootRoleProvider();

    @Test
    void declaresRootOwnRoles() {
        Set<String> names = provider.getDeclaredRoles().stream()
                .map(PlaintextRole::normalizedName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("root", "admin", "user"), names);
    }

    @Test
    void everyRoleHasDescription() {
        for (PlaintextRole role : provider.getDeclaredRoles()) {
            assertFalse(role.description().isEmpty(), "Rolle ohne Beschreibung: " + role.name());
        }
    }

    @Test
    void doesNotDeclareTechnicalSystemRole() {
        assertTrue(provider.getDeclaredRoles().stream()
                .noneMatch(r -> r.normalizedName().equals("system")),
                "ROLE_SYSTEM ist technisch und darf nicht als Benutzer-Rolle angeboten werden");
    }

    @Test
    void getRolesMatchesDeclaredRoles() {
        Set<String> fromGetRoles = provider.getRoles();
        Set<String> fromDeclared = provider.getDeclaredRoles().stream()
                .map(PlaintextRole::name)
                .collect(Collectors.toSet());

        assertEquals(fromDeclared, fromGetRoles);
    }
}

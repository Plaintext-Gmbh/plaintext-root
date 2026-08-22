/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests fuer den Rollen-Registry-Mechanismus (Modul-Rollen-Registrierung): Einsammeln der
 * Provider-Beans, Deduplizierung ueber den normalisierten Namen, Beschreibungs-Merge und
 * Verhalten ohne Provider.
 */
class PlaintextRoleRegistryTest {

    private PlaintextRoleRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PlaintextRoleRegistry();
    }

    private void setProviders(List<PlaintextRoleProvider> providers) throws Exception {
        java.lang.reflect.Field field = PlaintextRoleRegistry.class.getDeclaredField("roleProviders");
        field.setAccessible(true);
        field.set(registry, providers);
    }

    private PlaintextRoleProvider providerOf(PlaintextRole... roles) {
        PlaintextRoleProvider provider = mock(PlaintextRoleProvider.class);
        when(provider.getDeclaredRoles()).thenReturn(new java.util.LinkedHashSet<>(List.of(roles)));
        return provider;
    }

    @Nested
    @DisplayName("getDeclaredRoles")
    class GetDeclaredRoles {

        @Test
        void combinesRolesFromAllProviders() throws Exception {
            setProviders(List.of(
                    providerOf(new PlaintextRole("admin", "Administration")),
                    providerOf(new PlaintextRole("editor", "Redaktion"))));

            List<PlaintextRole> roles = registry.getDeclaredRoles();

            assertEquals(2, roles.size());
            assertEquals("admin", roles.get(0).normalizedName());
            assertEquals("editor", roles.get(1).normalizedName());
        }

        @Test
        void deduplicatesCaseAndPrefixInsensitive() throws Exception {
            setProviders(List.of(
                    providerOf(new PlaintextRole("ADMIN", "")),
                    providerOf(new PlaintextRole("ROLE_admin", "")),
                    providerOf(new PlaintextRole("admin", ""))));

            assertEquals(1, registry.getDeclaredRoles().size());
        }

        @Test
        void firstNonEmptyDescriptionWins() throws Exception {
            setProviders(List.of(
                    providerOf(new PlaintextRole("admin", "")),
                    providerOf(new PlaintextRole("ADMIN", "Administration")),
                    providerOf(new PlaintextRole("admin", "Andere Beschreibung"))));

            List<PlaintextRole> roles = registry.getDeclaredRoles();
            assertEquals(1, roles.size());
            assertEquals("Administration", roles.get(0).description());
        }

        @Test
        void sortsAlphabetically() throws Exception {
            setProviders(List.of(providerOf(
                    new PlaintextRole("zulu", ""),
                    new PlaintextRole("alpha", ""),
                    new PlaintextRole("mike", ""))));

            List<PlaintextRole> roles = registry.getDeclaredRoles();
            assertEquals(List.of("alpha", "mike", "zulu"),
                    roles.stream().map(PlaintextRole::normalizedName).toList());
        }

        @Test
        void emptyWithoutProviders() {
            assertTrue(registry.getDeclaredRoles().isEmpty());
            assertTrue(registry.getDeclaredRoleNames().isEmpty());
            assertTrue(registry.getDeclaredAuthorityNames().isEmpty());
        }

        @Test
        void toleratesFailingProvider() throws Exception {
            PlaintextRoleProvider broken = mock(PlaintextRoleProvider.class);
            when(broken.getDeclaredRoles()).thenThrow(new IllegalStateException("kaputt"));

            setProviders(List.of(broken, providerOf(new PlaintextRole("admin", ""))));

            assertEquals(1, registry.getDeclaredRoles().size());
        }

        @Test
        void toleratesNullDeclarations() throws Exception {
            PlaintextRoleProvider nullProvider = mock(PlaintextRoleProvider.class);
            when(nullProvider.getDeclaredRoles()).thenReturn(null);

            setProviders(List.of(nullProvider, providerOf(new PlaintextRole("admin", ""))));

            assertEquals(1, registry.getDeclaredRoles().size());
        }
    }

    @Nested
    @DisplayName("Namens-Formate")
    class NameFormats {

        @Test
        void declaredRoleNamesAreNormalized() throws Exception {
            setProviders(List.of(providerOf(
                    new PlaintextRole("ROLE_ADMIN", ""),
                    new PlaintextRole("Editor", ""))));

            Set<String> names = registry.getDeclaredRoleNames();
            assertEquals(Set.of("admin", "editor"), names);
        }

        @Test
        void authorityNamesCarryRolePrefix() throws Exception {
            setProviders(List.of(providerOf(
                    new PlaintextRole("admin", ""),
                    new PlaintextRole("ROLE_editor", ""))));

            Set<String> names = registry.getDeclaredAuthorityNames();
            assertEquals(Set.of("ROLE_ADMIN", "ROLE_EDITOR"), names);
        }
    }

    @Nested
    @DisplayName("getDescription")
    class GetDescription {

        @Test
        void findsDescriptionIgnoringCaseAndPrefix() throws Exception {
            setProviders(List.of(providerOf(new PlaintextRole("admin", "Administration"))));

            assertEquals("Administration", registry.getDescription("admin"));
            assertEquals("Administration", registry.getDescription("ADMIN"));
            assertEquals("Administration", registry.getDescription("ROLE_ADMIN"));
        }

        @Test
        void emptyForUnknownOrBlank() throws Exception {
            setProviders(List.of(providerOf(new PlaintextRole("admin", "Administration"))));

            assertEquals("", registry.getDescription("unbekannt"));
            assertEquals("", registry.getDescription(null));
            assertEquals("", registry.getDescription("  "));
        }
    }

    @Nested
    @DisplayName("PlaintextRoleProvider-Default und PlaintextRole")
    class ProviderDefaultsAndRole {

        @Test
        void defaultDeclaredRolesDeriveFromGetRoles() {
            PlaintextRoleProvider provider = () -> new java.util.LinkedHashSet<>(List.of("ROLE_A", "b"));

            Set<PlaintextRole> declared = provider.getDeclaredRoles();

            assertEquals(2, declared.size());
            assertTrue(declared.stream().allMatch(r -> r.description().isEmpty()));
            assertTrue(declared.stream().anyMatch(r -> r.normalizedName().equals("a")));
            assertTrue(declared.stream().anyMatch(r -> r.normalizedName().equals("b")));
        }

        @Test
        void roleNormalizationAndAuthority() {
            PlaintextRole role = new PlaintextRole("ROLE_Admin", " Beschreibung ");
            assertEquals("admin", role.normalizedName());
            assertEquals("ROLE_ADMIN", role.authorityName());
            assertEquals("Beschreibung", role.description());
        }

        @Test
        void blankNameRejected() {
            assertThrows(IllegalArgumentException.class, () -> new PlaintextRole("  ", "x"));
            assertThrows(IllegalArgumentException.class, () -> new PlaintextRole(null, "x"));
        }
    }
}

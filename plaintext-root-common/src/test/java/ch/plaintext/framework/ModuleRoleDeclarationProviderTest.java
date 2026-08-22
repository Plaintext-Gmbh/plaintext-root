/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import ch.plaintext.boot.menu.ModuleRoleProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die per Konfiguration vergebenen Modul-Rollen muessen ohne Zutun der App in der
 * {@link PlaintextRoleRegistry} landen — sonst stehen sie in der Benutzerverwaltung nicht zur
 * Auswahl und koennen niemandem vergeben werden.
 */
class ModuleRoleDeclarationProviderTest {

    private static ModuleRoleDeclarationProvider provider(Map<String, String> konfiguration) {
        ModuleRoleProperties properties = new ModuleRoleProperties();
        properties.setModuleRoles(new LinkedHashMap<>(konfiguration));
        return new ModuleRoleDeclarationProvider(properties);
    }

    @Test
    void ohneKonfigurationWerdenKeineRollenDeklariert() {
        assertTrue(provider(Map.of()).getDeclaredRoles().isEmpty());
        assertTrue(new ModuleRoleDeclarationProvider().getDeclaredRoles().isEmpty(),
                "kein ModuleRoleProperties-Bean -> keine Rollen, kein NPE");
    }

    @Test
    void konfigurierteRolleWirdMitGenerierterBeschreibungDeklariert() {
        Set<PlaintextRole> rollen = provider(Map.of("wiki", "wiki")).getDeclaredRoles();

        assertEquals(1, rollen.size());
        PlaintextRole rolle = rollen.iterator().next();
        assertEquals("wiki", rolle.name());
        assertEquals("wiki", rolle.normalizedName());
        assertEquals("ROLE_WIKI", rolle.authorityName());
        assertEquals("Zugriff auf das Modul wiki", rolle.description());
    }

    @Test
    void eineRolleFuerMehrereModuleWirdEinmalDeklariert() {
        Map<String, String> konfiguration = new LinkedHashMap<>();
        konfiguration.put("rechnungen", "finanzen");
        konfiguration.put("buchhaltung", "finanzen");
        konfiguration.put("postkonto", "FINANZEN");

        Set<PlaintextRole> rollen = provider(konfiguration).getDeclaredRoles();

        assertEquals(1, rollen.size());
        PlaintextRole rolle = rollen.iterator().next();
        assertEquals("finanzen", rolle.normalizedName());
        assertEquals("Zugriff auf die Module buchhaltung, postkonto, rechnungen", rolle.description());
    }

    @Test
    void getRolesBleibtKonsistentZuGetDeclaredRoles() {
        assertEquals(Set.of("mail", "wiki"), provider(Map.of("mailbox", "mail", "wiki", "WIKI")).getRoles());
    }

    @Test
    void registryUebernimmtDieKonfiguriertenRollen() {
        PlaintextRoleRegistry registry = new PlaintextRoleRegistry();
        setProviders(registry, List.of(provider(Map.of("mailbox", "mail"))));

        assertTrue(registry.getDeclaredRoleNames().contains("mail"));
        assertEquals("Zugriff auf das Modul mailbox", registry.getDescription("ROLE_MAIL"));
    }

    @Test
    void eigenerAppProviderGewinntMitSeinerBeschreibung() {
        PlaintextRoleRegistry registry = new PlaintextRoleRegistry();
        PlaintextRoleProvider appProvider = () -> Set.of("finanzen");
        PlaintextRoleProvider ausfuehrlich = new PlaintextRoleProvider() {
            @Override
            public Set<String> getRoles() {
                return Set.of("finanzen");
            }

            @Override
            public Set<PlaintextRole> getDeclaredRoles() {
                return Set.of(new PlaintextRole("finanzen", "Buchhaltung und Rechnungen (Schreibrechte)"));
            }
        };
        setProviders(registry, List.of(ausfuehrlich, provider(Map.of("buchhaltung", "finanzen"))));

        assertEquals("Buchhaltung und Rechnungen (Schreibrechte)", registry.getDescription("finanzen"));
        assertEquals(1, registry.getDeclaredRoles().size());
        assertTrue(appProvider.getRoles().contains("finanzen"));
    }

    private static void setProviders(PlaintextRoleRegistry registry, List<PlaintextRoleProvider> providers) {
        try {
            java.lang.reflect.Field field = PlaintextRoleRegistry.class.getDeclaredField("roleProviders");
            field.setAccessible(true);
            field.set(registry, providers);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

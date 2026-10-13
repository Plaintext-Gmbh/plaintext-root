/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.security;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.menu.ModuleRoleProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Karte 1500: dieselbe Regel wie der Page-Guard, die Zuordnung aus der Konfiguration. */
class ModulRolleTest {

    private static ModulRolle modulRolle(Map<String, String> zuordnung, String... rollen) {
        ModuleRoleProperties p = new ModuleRoleProperties();
        p.setModuleRoles(zuordnung);
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        bf.addBean("props", p);
        if (rollen != null) {
            Set<String> hat = Set.of(rollen);
            PlaintextSecurity s = mock(PlaintextSecurity.class);
            // wie PlaintextSecurityImpl.ifGranted: ROLE_-Praefix optional, Gross/Klein egal
            when(s.ifGranted(anyString())).thenAnswer(a -> {
                String r = a.getArgument(0, String.class).toUpperCase();
                return hat.contains(r.startsWith("ROLE_") ? r : "ROLE_" + r);
            });
            bf.addBean("security", s);
        }
        return new ModulRolle(bf.getBeanProvider(ModuleRoleProperties.class), bf.getBeanProvider(PlaintextSecurity.class));
    }

    @Test
    @DisplayName("Mit der konfigurierten Rolle (klein geschrieben wie in application.yml) darf man")
    void mitRolle() {
        assertThat(modulRolle(Map.of("fotos", "fotos"), "ROLE_USER", "ROLE_FOTOS").darf("fotos")).isTrue();
    }

    @Test
    @DisplayName("Ohne die Rolle nicht, auch mit einer anderen Modulrolle")
    void ohneRolle() {
        assertThat(modulRolle(Map.of("fotos", "fotos"), "ROLE_USER", "ROLE_WIKI").darf("fotos")).isFalse();
    }

    @Test
    @DisplayName("ADMIN und ROOT immer")
    void admin() {
        assertThat(modulRolle(Map.of("fotos", "fotos"), "ROLE_ADMIN").darf("fotos")).isTrue();
        assertThat(modulRolle(Map.of("fotos", "fotos"), "ROLE_ROOT").darf("fotos")).isTrue();
    }

    @Test
    @DisplayName("Andere Instanz, andere Rolle: guild ordnet auszahlungen der Rolle auszahlung zu")
    void zuordnungAusKonfiguration() {
        Map<String, String> guild = Map.of("auszahlungen", "auszahlung");
        assertThat(modulRolle(guild, "ROLE_USER", "ROLE_AUSZAHLUNG").darf("auszahlungen")).isTrue();
        assertThat(modulRolle(guild, "ROLE_USER", "ROLE_AUSZAHLUNGEN").darf("auszahlungen")).isFalse();
    }

    @Test
    @DisplayName("Modul ohne Zuordnung bleibt frei wie bisher")
    void ohneZuordnung() {
        assertThat(modulRolle(Map.of("wiki", "wiki"), "ROLE_USER").darf("fotos")).isTrue();
    }

    @Test
    @DisplayName("Ohne PlaintextSecurity und mit Zuordnung: zu")
    void ohneSecurity() {
        assertThat(modulRolle(Map.of("fotos", "fotos"), (String[]) null).darf("fotos")).isFalse();
    }
}

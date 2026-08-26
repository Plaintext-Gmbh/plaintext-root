/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Meldung Daniel, 26.08.2026: „Auszahlungen für Jasmin Marthaler in Mandat trimstein geht nicht
 * mehr."
 *
 * <p><b>Die Kette.</b> Rollen stehen in der Datenbank klein ({@code auszahlungen}).
 * {@code MyUserDetailsService} macht daraus beim Anmelden {@code "ROLE_" + role.toUpperCase()},
 * also {@code ROLE_AUSZAHLUNGEN}. Die Werte in {@code plaintext.menu.module-roles} sind klein
 * geschrieben, und {@code SpringSecurityProvider.hasRole} vergleicht bewusst mit Beachtung der
 * Schreibweise. Der Riegel traf damit <b>nie</b> zu.
 *
 * <p>Die Folge war schlimmer als „ein Modul fehlt": das Modul war für jeden ausser ROOT/ADMIN
 * dauerhaft unsichtbar — auch für die Person, der man die Rolle ausdrücklich zugewiesen hatte.
 * Der Riegel liess sich schliessen, aber nicht mehr öffnen.
 */
class ModulRiegelSchreibweiseTest {

    /** Ein Benutzer mit genau den Authorities, die {@code MyUserDetailsService} erzeugt. */
    private static SecurityProvider angemeldetMit(String... authorities) {
        Set<String> vorhanden = Set.of(authorities);
        return new SecurityProvider() {
            @Override
            public boolean hasRole(String role) {
                // Absichtlich mit Beachtung der Schreibweise - genau wie SpringSecurityProvider.
                return vorhanden.contains(role)
                        || vorhanden.contains(role.startsWith("ROLE_") ? role.substring(5) : "ROLE_" + role);
            }

            @Override
            public boolean isSecurityEnabled() {
                return true;
            }
        };
    }

    @Test
    @DisplayName("Der gemeldete Fall: Rolle klein konfiguriert, Authority gross")
    void kleinKonfigurierteRolleTrifftGrossgeschriebeneAuthority() {
        SecurityProvider jasmin = angemeldetMit("ROLE_AUSZAHLUNGEN", "ROLE_USER", "PROPERTY_MANDAT_TRIMSTEIN");

        assertThat(ModuleRoleService.holdsAny(List.of("auszahlungen"), jasmin))
                .as("so steht es in plaintext.menu.module-roles - vorher immer false")
                .isTrue();
    }

    @Test
    @DisplayName("Auch gross konfiguriert trifft weiterhin")
    void grossKonfigurierteRolleTrifftWeiterhin() {
        SecurityProvider wer = angemeldetMit("ROLE_POSTKONTO");

        assertThat(ModuleRoleService.holdsAny(List.of("POSTKONTO"), wer))
                .as("die Menue-Annotationen im Bestand schreiben gross - das darf nicht kaputtgehen")
                .isTrue();
    }

    /**
     * Die Gegenprobe, ohne die „immer true" ebenfalls grün wäre: die Schreibweise darf
     * durchlässig sein, der Rollenname nicht.
     */
    @Test
    @DisplayName("Eine fremde Rolle bleibt verweigert")
    void fremdeRolleBleibtVerweigert() {
        SecurityProvider jasmin = angemeldetMit("ROLE_AUSZAHLUNGEN", "ROLE_USER");

        assertThat(ModuleRoleService.holdsAny(List.of("lauftage"), jasmin)).isFalse();
        assertThat(ModuleRoleService.holdsAny(List.of("wiki"), jasmin)).isFalse();
        assertThat(ModuleRoleService.holdsAny(List.of("wanderungen", "running"), jasmin)).isFalse();
    }

    @Test
    @DisplayName("ROOT und ADMIN behalten Zugriff - unveraendert")
    void rootUndAdminUnveraendert() {
        assertThat(ModuleRoleService.holdsAny(List.of("auszahlungen"), angemeldetMit("ROLE_ROOT"))).isTrue();
        assertThat(ModuleRoleService.holdsAny(List.of("auszahlungen"), angemeldetMit("ROLE_ADMIN"))).isTrue();
    }

    @Test
    @DisplayName("Ohne geforderte Rolle bleibt alles offen")
    void ohneAnforderungOffen() {
        SecurityProvider ohneAlles = angemeldetMit("ROLE_USER");

        assertThat(ModuleRoleService.holdsAny(null, ohneAlles)).isTrue();
        assertThat(ModuleRoleService.holdsAny(List.of(), ohneAlles)).isTrue();
    }
}

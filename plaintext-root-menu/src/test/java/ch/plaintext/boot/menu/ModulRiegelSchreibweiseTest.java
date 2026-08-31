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
 * Report from Daniel, 26.08.2026: "Auszahlungen for Jasmin Marthaler in tenant trimstein no
 * longer works."
 *
 * <p><b>The chain.</b> Roles are stored in lower case in the database ({@code auszahlungen}). At
 * login {@code MyUserDetailsService} turns them into {@code "ROLE_" + role.toUpperCase()}, that is
 * {@code ROLE_AUSZAHLUNGEN}. The values in {@code plaintext.menu.module-roles} are written in
 * lower case, and {@code SpringSecurityProvider.hasRole} compares case-sensitively on purpose. The
 * gate therefore <b>never</b> matched.
 *
 * <p>The consequence was worse than "a module is missing": the module was permanently invisible to
 * everyone except ROOT/ADMIN — including the person the role had explicitly been assigned to. The
 * gate could be closed, but no longer opened.
 */
class ModulRiegelSchreibweiseTest {

    /** A user with exactly the authorities that {@code MyUserDetailsService} produces. */
    private static SecurityProvider angemeldetMit(String... authorities) {
        Set<String> vorhanden = Set.of(authorities);
        return new SecurityProvider() {
            @Override
            public boolean hasRole(String role) {
                // Deliberately case-sensitive - exactly like SpringSecurityProvider.
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
     * The control test, without which "always true" would be green as well: the spelling may be
     * permissive, the role name may not.
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

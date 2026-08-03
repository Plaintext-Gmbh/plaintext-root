/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Covers the STRICT access policy and the roles derived from a menu item.
 * <p>
 * The derived role name is persisted against users, so the derivation formula is a contract:
 * changing it silently revokes access that was granted earlier. These tests pin it down.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MenuAccessPolicyTest {

    @Mock
    private SecurityProvider securityProvider;

    private MenuItemImpl item(String parent, String title, MenuAccessPolicy policy) {
        MenuItemImpl m = new MenuItemImpl();
        m.setParent(parent);
        m.setTitle(title);
        m.setAccessPolicy(policy);
        m.setSecurityProvider(securityProvider);
        return m;
    }

    @Nested
    class Rollenableitung {

        @Test
        void leitetDieRolleAusElternUndTitelAb() {
            assertEquals("ROLE_MENU_ENTITAETEN_REPOS",
                    item("Entitäten", "Repos", MenuAccessPolicy.STRICT).getAutoRole());
        }

        @Test
        void transliteriertUmlauteUndScharfesS() {
            MenuItemImpl m = item("", "Grüße Öl Ähre", MenuAccessPolicy.STRICT);
            assertEquals("gruesse_oel_aehre", m.getEffectiveMenuId());
        }

        @Test
        void fasstSonderzeichenZuEinemUnterstrichZusammen() {
            assertEquals("a_b", item("", "a --- b", MenuAccessPolicy.STRICT).getEffectiveMenuId());
        }

        @Test
        void schneidetFuehrendeUndAbschliessendeUnterstricheAb() {
            assertEquals("titel", item("", " !Titel! ", MenuAccessPolicy.STRICT).getEffectiveMenuId());
        }

        @Test
        void bevorzugtDenExplizitenMenuId() {
            MenuItemImpl m = item("Entitäten", "Repos", MenuAccessPolicy.STRICT);
            m.setMenuId("stabil");
            assertEquals("stabil", m.getEffectiveMenuId());
            assertEquals("ROLE_MENU_STABIL", m.getAutoRole());
        }
    }

    @Nested
    class Strict {

        @Test
        void verbirgtOhneJedeTreffendeRegel() {
            assertFalse(item("Entitäten", "Repos", MenuAccessPolicy.STRICT).isOn());
        }

        @Test
        void zeigtDerAbgeleitetenRolle() {
            when(securityProvider.hasRole("ROLE_MENU_ENTITAETEN_REPOS")).thenReturn(true);
            assertTrue(item("Entitäten", "Repos", MenuAccessPolicy.STRICT).isOn());
        }

        @Test
        void zeigtRoot() {
            when(securityProvider.hasRole(MenuItemImpl.ROLE_ROOT)).thenReturn(true);
            assertTrue(item("Root", "Debug", MenuAccessPolicy.STRICT).isOn());
        }

        @Test
        void haeltDasRootMenueVorAdminGeschlossen() {
            when(securityProvider.hasRole(MenuItemImpl.ROLE_ADMIN)).thenReturn(true);
            assertFalse(item("Root", "Debug", MenuAccessPolicy.STRICT).isOn());
            assertTrue(item("Entitäten", "Repos", MenuAccessPolicy.STRICT).isOn());
        }

        @Test
        void zeigtBeiPassendemRollenpraefix() {
            when(securityProvider.hasAnyRoleStartingWith("MEMBER_")).thenReturn(true);
            MenuItemImpl m = item("Teams", "Meine", MenuAccessPolicy.STRICT);
            m.setRoleStartsWith(List.of("MEMBER_"));
            assertTrue(m.isOn());
        }

        @Test
        void zeigtOhneSecurityProviderAlles() {
            MenuItemImpl m = item("Entitäten", "Repos", MenuAccessPolicy.STRICT);
            m.setSecurityProvider(null);
            assertTrue(m.isOn());
        }
    }

    @Nested
    class Permissive {

        @Test
        void bleibtOhneRollenSichtbar() {
            assertTrue(item("Entitäten", "Repos", MenuAccessPolicy.PERMISSIVE).isOn());
        }

        @Test
        void verbirgtNurBeiNichtPassenderRollenliste() {
            MenuItemImpl m = item("Entitäten", "Repos", MenuAccessPolicy.PERMISSIVE);
            m.setRoles(List.of("ROLE_X"));
            assertFalse(m.isOn());
        }

        @Test
        void istDieVorgabe() {
            assertEquals(MenuAccessPolicy.PERMISSIVE, new MenuItemImpl().getAccessPolicy());
        }
    }

    @Nested
    class Konfiguration {

        @Test
        void faelltBeiUnbekanntemWertAufPermissiveZurueck() {
            assertEquals(MenuAccessPolicy.PERMISSIVE, MenuAccessPolicy.from("tippfehler"));
            assertEquals(MenuAccessPolicy.PERMISSIVE, MenuAccessPolicy.from(null));
            assertEquals(MenuAccessPolicy.PERMISSIVE, MenuAccessPolicy.from("  "));
        }

        @Test
        void liestBeideWerteUnabhaengigVonGrossschreibung() {
            assertEquals(MenuAccessPolicy.STRICT, MenuAccessPolicy.from("strict"));
            assertEquals(MenuAccessPolicy.STRICT, MenuAccessPolicy.from(" STRICT "));
            assertEquals(MenuAccessPolicy.PERMISSIVE, MenuAccessPolicy.from("Permissive"));
        }
    }
}

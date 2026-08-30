/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.boot.menu.ModuleRoleProperties;
import ch.plaintext.boot.menu.ModuleRoleService;
import ch.plaintext.boot.menu.SecurityProvider;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tiles must disappear consistently with their module menu: a tile that points at a page of a
 * module protected via {@code plaintext.menu.module-roles} must not appear on the dashboard
 * without that role.
 */
class TileItemModuleRoleTest {

    private static SecurityProvider security(Set<String> rollen) {
        Set<String> gross = rollen.stream().map(r -> r.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        return rolle -> {
            if (rolle == null) {
                return false;
            }
            String mit = rolle.toUpperCase(Locale.ROOT);
            String ohne = mit.startsWith("ROLE_") ? mit.substring(5) : mit;
            return gross.contains(mit) || gross.contains(ohne);
        };
    }

    private static ModuleRoleService service(Map<String, String> konfiguration) {
        MenuItemImpl wurzel = new MenuItemImpl();
        wurzel.setTitle("Wiki");
        wurzel.setCommand("wiki.html");
        wurzel.setModuleId("wiki");

        MenuItemImpl kontakte = new MenuItemImpl();
        kontakte.setTitle("Kontakte");
        kontakte.setCommand("kontakte.html");
        kontakte.setModuleId("kontakte");

        ModuleRoleProperties properties = new ModuleRoleProperties();
        properties.setModuleRoles(new LinkedHashMap<>(konfiguration));
        ModuleRoleService service = new ModuleRoleService(null, properties);
        service.resolve(List.of(wurzel, kontakte));
        return service;
    }

    private static TileItemImpl kachel(String link, String menuTitle, ModuleRoleService service,
                                       SecurityProvider securityProvider) {
        TileItemImpl tile = new TileItemImpl();
        tile.setId(link);
        tile.setTitle(menuTitle);
        tile.setLink(link);
        tile.setMenuTitle(menuTitle);
        tile.setModuleRoleService(service);
        tile.setSecurityProvider(securityProvider);
        return tile;
    }

    @Test
    void kachelOhneModulRolleIstUnsichtbar() {
        ModuleRoleService service = service(Map.of("wiki", "wiki"));

        assertFalse(kachel("wiki.html", "Wiki", service, security(Set.of("USER"))).isOn());
    }

    @Test
    void kachelMitModulRolleIstSichtbar() {
        ModuleRoleService service = service(Map.of("wiki", "wiki"));

        assertTrue(kachel("wiki.html", "Wiki", service, security(Set.of("USER", "WIKI"))).isOn());
    }

    @Test
    void adminUndRootSehenDieKachelImmer() {
        ModuleRoleService service = service(Map.of("wiki", "wiki"));

        assertTrue(kachel("wiki.html", "Wiki", service, security(Set.of("ADMIN"))).isOn());
        assertTrue(kachel("wiki.html", "Wiki", service, security(Set.of("ROOT"))).isOn());
    }

    @Test
    void kachelEinesUnkonfiguriertenModulsBleibtSichtbar() {
        ModuleRoleService service = service(Map.of("wiki", "wiki"));

        assertTrue(kachel("kontakte.html", "Kontakte", service, security(Set.of("USER"))).isOn());
    }

    @Test
    void ohneServiceVerhaeltSichDieKachelWieBisher() {
        TileItemImpl tile = kachel("wiki.html", "Wiki", null, security(Set.of("USER")));

        assertTrue(tile.isOn());
    }
}

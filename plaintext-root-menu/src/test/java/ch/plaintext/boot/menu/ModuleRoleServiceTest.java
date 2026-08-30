/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configurable module roles ({@code plaintext.menu.module-roles}).
 *
 * <p>What is reproduced here is the real structure of a bundled module: a root menu with a
 * {@code moduleId} and children that hang off it only via {@code parent} and themselves declare
 * {@code roles = {USER, ADMIN, ROOT}} — exactly the constellation in which a module has to be
 * switchable off entirely without any change to the module code.
 */
class ModuleRoleServiceTest {

    private static final Set<String> USER = Set.of("USER");
    private static final Set<String> USER_MIT_WIKI = Set.of("USER", "WIKI");
    private static final Set<String> ADMIN = Set.of("USER", "ADMIN");
    private static final Set<String> ROOT = Set.of("ROOT");

    // ---------------------------------------------------------------- Helpers

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

    private static MenuItemImpl menu(String titel, String parent, String link, String moduleId, Set<String> rollen) {
        MenuItemImpl item = new MenuItemImpl();
        item.setTitle(titel);
        item.setParent(parent == null ? "" : parent);
        item.setCommand(link == null ? "" : link);
        item.setModuleId(moduleId == null ? "" : moduleId);
        item.setRoles(List.of("USER", "ADMIN", "ROOT"));
        item.setSecurityProvider(security(rollen));
        return item;
    }

    /** Wiki module (root with moduleId + two children) and an unconfigured Kontakte module. */
    private static List<MenuItemImpl> menueBaum(Set<String> rollen) {
        return List.of(
                menu("Wiki", "", "wiki.html", "wiki", rollen),
                menu("Projekte", "Wiki", "wiki-projekte.html", "", rollen),
                menu("Einstellungen", "Wiki", "wiki-einstellungen.html", "", rollen),
                menu("Kontakte", "", "kontakte.html", "kontakte", rollen),
                menu("Liste", "Kontakte", "kontakte-liste.html", "", rollen));
    }

    private static ModuleRoleService service(Map<String, String> konfiguration, List<MenuItemImpl> menues) {
        ModuleRoleProperties properties = new ModuleRoleProperties();
        properties.setModuleRoles(new LinkedHashMap<>(konfiguration));
        ModuleRoleService service = new ModuleRoleService(null, properties);
        service.resolve(menues);
        return service;
    }

    private static MenuItemImpl finde(List<MenuItemImpl> menues, String titel) {
        return menues.stream().filter(m -> titel.equals(m.getTitle())).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- Menu

    @Test
    void ohneKonfigurationBleibtAllesWieBisher() {
        List<MenuItemImpl> menues = menueBaum(USER);
        service(Map.of(), menues);

        for (MenuItemImpl item : menues) {
            assertTrue(item.getModuleRoles().isEmpty(), "unkonfiguriert -> keine Modul-Rolle: " + item.getTitle());
            assertTrue(item.isOn(), "unkonfiguriert -> unveraendert sichtbar: " + item.getTitle());
        }
    }

    @Test
    void ohneKonfigurierteRolleVerschwindetDasGanzeModul() {
        List<MenuItemImpl> menues = menueBaum(USER);
        service(Map.of("wiki", "wiki"), menues);

        assertFalse(finde(menues, "Wiki").isOn(), "Wurzelmenue des Moduls");
        assertFalse(finde(menues, "Projekte").isOn(), "Kind ohne eigene moduleId");
        assertFalse(finde(menues, "Einstellungen").isOn(), "Kind ohne eigene moduleId");
    }

    @Test
    void mitKonfigurierterRolleIstDasModulSichtbar() {
        List<MenuItemImpl> menues = menueBaum(USER_MIT_WIKI);
        service(Map.of("wiki", "wiki"), menues);

        assertTrue(finde(menues, "Wiki").isOn());
        assertTrue(finde(menues, "Projekte").isOn());
        assertTrue(finde(menues, "Einstellungen").isOn());
    }

    @Test
    void unkonfigurierteModuleBleibenUnberuehrt() {
        List<MenuItemImpl> menues = menueBaum(USER);
        service(Map.of("wiki", "wiki"), menues);

        assertTrue(finde(menues, "Kontakte").isOn(), "anderes Modul bleibt offen");
        assertTrue(finde(menues, "Liste").isOn());
        assertTrue(finde(menues, "Kontakte").getModuleRoles().isEmpty());
    }

    @Test
    void adminUndRootBehaltenImmerZugriff() {
        List<MenuItemImpl> alsAdmin = menueBaum(ADMIN);
        service(Map.of("wiki", "wiki"), alsAdmin);
        assertTrue(finde(alsAdmin, "Wiki").isOn(), "admin umgeht die Modul-Rolle");
        assertTrue(finde(alsAdmin, "Projekte").isOn());

        List<MenuItemImpl> alsRoot = menueBaum(ROOT);
        service(Map.of("wiki", "wiki"), alsRoot);
        assertTrue(finde(alsRoot, "Wiki").isOn(), "root umgeht die Modul-Rolle");
        assertTrue(finde(alsRoot, "Projekte").isOn());
    }

    @Test
    void gleicheRolleFuerMehrereModuleWirktAufAlle() {
        List<MenuItemImpl> menues = menueBaum(USER);
        service(Map.of("wiki", "finanzen", "kontakte", "finanzen"), menues);

        assertFalse(finde(menues, "Wiki").isOn());
        assertFalse(finde(menues, "Kontakte").isOn());

        List<MenuItemImpl> mitRolle = menueBaum(Set.of("USER", "FINANZEN"));
        service(Map.of("wiki", "finanzen", "kontakte", "finanzen"), mitRolle);
        assertTrue(finde(mitRolle, "Wiki").isOn());
        assertTrue(finde(mitRolle, "Kontakte").isOn());
    }

    // ---------------------------------------------------------------- Module key

    @Test
    void modulKeyIstDieModuleIdUndAlsFallbackDieMenuRootId() {
        // "Rechnungsverwaltung" carries moduleId="rechnungen" - both keys must apply.
        List<MenuItemImpl> menues = List.of(
                menu("Rechnungsverwaltung", "", "rechnungen.html", "rechnungen", USER),
                menu("Rechnungen", "Rechnungsverwaltung", "rechnungen.html", "", USER));
        ModuleRoleService service = service(Map.of(), menues);
        assertTrue(service.getKnownModuleKeys().containsAll(Set.of("rechnungen", "rechnungsverwaltung")),
                "erkannt: " + service.getKnownModuleKeys());

        List<MenuItemImpl> ueberModuleId = List.of(
                menu("Rechnungsverwaltung", "", "rechnungen.html", "rechnungen", USER),
                menu("Rechnungen", "Rechnungsverwaltung", "rechnungen.html", "", USER));
        service(Map.of("rechnungen", "finanzen"), ueberModuleId);
        assertFalse(finde(ueberModuleId, "Rechnungen").isOn(), "Key = moduleId");

        List<MenuItemImpl> ueberMenuRootId = List.of(
                menu("Rechnungsverwaltung", "", "rechnungen.html", "rechnungen", USER),
                menu("Rechnungen", "Rechnungsverwaltung", "rechnungen.html", "", USER));
        service(Map.of("rechnungsverwaltung", "finanzen"), ueberMenuRootId);
        assertFalse(finde(ueberMenuRootId, "Rechnungen").isOn(), "Key = Menu-Root-Id");
    }

    @Test
    void modulOhneModuleIdIstUeberDieMenuRootIdKonfigurierbar() {
        List<MenuItemImpl> menues = List.of(
                menu("Mein Modul", "", "meinmodul.html", "", USER),
                menu("Detail", "Mein Modul", "meinmodul-detail.html", "", USER));
        service(Map.of("mein_modul", "spezial"), menues);

        assertFalse(finde(menues, "Mein Modul").isOn());
        assertFalse(finde(menues, "Detail").isOn(), "Kind erbt den Key ueber die Elternkette");
    }

    @Test
    void zyklischeElternketteFuehrtNichtInEineEndlosschleife() {
        MenuItemImpl a = menu("A", "B", "a.html", "", USER);
        MenuItemImpl b = menu("B", "A", "b.html", "", USER);
        ModuleRoleService service = service(Map.of(), List.of(a, b));

        assertFalse(service.getKnownModuleKeys().isEmpty());
    }

    @Test
    void grossKleinschreibungUndRolePrefixSindEgal() {
        List<MenuItemImpl> menues = menueBaum(USER_MIT_WIKI);
        service(Map.of("WIKI", "ROLE_Wiki"), menues);

        assertEquals(List.of("WIKI"), finde(menues, "Wiki").getModuleRoles());
        assertTrue(finde(menues, "Wiki").isOn());
    }

    // ---------------------------------------------------------------- Tiles

    @Test
    void kachelVerschwindetMitIhremModul() {
        List<MenuItemImpl> menues = menueBaum(USER);
        ModuleRoleService service = service(Map.of("wiki", "wiki"), menues);

        assertFalse(service.isAllowedForLink("wiki.html", "Wiki", security(USER)),
                "Kachel des Wiki-Moduls ohne Rolle");
        assertFalse(service.isAllowedForLink("/wiki.xhtml", "Wiki", security(USER)),
                "Link-Normalisierung (.xhtml, fuehrender Slash)");
        assertTrue(service.isAllowedForLink("wiki.html", "Wiki", security(USER_MIT_WIKI)),
                "Kachel mit Rolle");
        assertTrue(service.isAllowedForLink("wiki.html", "Wiki", security(ADMIN)),
                "admin umgeht die Modul-Rolle");
        assertTrue(service.isAllowedForLink("kontakte.html", "Kontakte", security(USER)),
                "Kachel eines unkonfigurierten Moduls");
    }

    @Test
    void kachelOhneLinkTrefferFaelltAufDenMenuTitelZurueck() {
        List<MenuItemImpl> menues = menueBaum(USER);
        ModuleRoleService service = service(Map.of("wiki", "wiki"), menues);

        assertFalse(service.isAllowedForLink("wiki-kachel-eigene-seite.html", "Projekte", security(USER)));
        assertTrue(service.isAllowedForLink("wiki-kachel-eigene-seite.html", "Projekte", security(USER_MIT_WIKI)));
    }

    @Test
    void ohneKonfigurationSindAlleKachelnSichtbar() {
        ModuleRoleService service = service(Map.of(), menueBaum(USER));

        assertTrue(service.isAllowedForLink("wiki.html", "Wiki", security(USER)));
        assertTrue(service.isAllowedForLink("was-auch-immer.html", "", null));
    }
}

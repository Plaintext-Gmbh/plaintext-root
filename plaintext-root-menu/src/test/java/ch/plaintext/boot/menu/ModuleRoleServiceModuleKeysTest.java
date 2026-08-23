/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der {@link ModuleRoleService} schreibt neben den geforderten Rollen auch die <b>Modul-Keys</b> an
 * jeden Menuepunkt.
 *
 * <p>Damit sprechen die beiden Zustaendigen dasselbe Vokabular: admin steuert ueber die Modul-Rollen,
 * WER ein Modul benutzen darf; root steuert ueber die Mandanten-Listen, WELCHE Module zu einem
 * Mandanten gehoeren. Beide identifizieren ein Modul ueber denselben Key.</p>
 */
@DisplayName("ModuleRoleService fuellt die Modul-Keys")
class ModuleRoleServiceModuleKeysTest {

    private static MenuItemImpl menu(String titel, String parent, String moduleId) {
        MenuItemImpl item = new MenuItemImpl();
        item.setTitle(titel);
        item.setParent(parent == null ? "" : parent);
        item.setCommand(titel.toLowerCase(java.util.Locale.ROOT) + ".html");
        item.setModuleId(moduleId == null ? "" : moduleId);
        return item;
    }

    private static MenuItemImpl finde(List<MenuItemImpl> menues, String titel) {
        return menues.stream().filter(m -> titel.equals(m.getTitle())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Ein Kind erbt den Modul-Key seines Wurzelmenues")
    void kindErbtModulKey() {
        List<MenuItemImpl> baum = List.of(
                menu("Wiki", "", "wiki"),
                menu("Projekte", "Wiki", ""),
                menu("Kontakte", "", "kontakte"));

        new ModuleRoleService(null, new ModuleRoleProperties()).resolve(baum);

        assertTrue(finde(baum, "Wiki").getModuleKeys().contains("wiki"));
        assertTrue(finde(baum, "Projekte").getModuleKeys().contains("wiki"),
                "Untermenues muessen ueber den Modul-Key des Wurzelmenues erreichbar sein — "
                        + "sonst schaltet ein Modul-Eintrag der Mandanten-Liste sie nicht mit");
        assertTrue(finde(baum, "Kontakte").getModuleKeys().contains("kontakte"));
    }

    @Test
    @DisplayName("Ohne moduleId greift die Menu-Root-Id als Key")
    void fallbackAufMenuRootId() {
        List<MenuItemImpl> baum = List.of(
                menu("Rechnungsverwaltung", "", ""),
                menu("Offene Posten", "Rechnungsverwaltung", ""));

        new ModuleRoleService(null, new ModuleRoleProperties()).resolve(baum);

        assertEquals(List.of("rechnungsverwaltung"), finde(baum, "Rechnungsverwaltung").getModuleKeys());
        assertEquals(List.of("rechnungsverwaltung"), finde(baum, "Offene Posten").getModuleKeys());
    }

    @Test
    @DisplayName("Modul-Keys sind kanonisch (kleingeschrieben)")
    void keysSindKanonisch() {
        List<MenuItemImpl> baum = List.of(menu("Wiki", "", "WiKi"));

        new ModuleRoleService(null, new ModuleRoleProperties()).resolve(baum);

        assertEquals(List.of("wiki"), finde(baum, "Wiki").getModuleKeys());
    }
}

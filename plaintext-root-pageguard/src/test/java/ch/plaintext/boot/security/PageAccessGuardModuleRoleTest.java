/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.boot.menu.ModuleRoleProperties;
import ch.plaintext.boot.menu.ModuleRoleService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configurable module roles must also deny the <em>direct call</em> of the pages, not just hide
 * the menu item.
 *
 * <p>The proof is not trivial: the children of a module menu typically declare
 * {@code roles = {USER, ADMIN, ROOT}} themselves (the wiki module, for example). This means the
 * guard's parent inheritance explicitly does <em>not</em> apply
 * ({@code PageAccessGuardService#istSichtbarMitEltern}: an item's own {@code roles} are final).
 * The protection can therefore only work because the module role is part of
 * {@link MenuItemImpl#isOn()} itself — and {@code isOn()} is checked before the inheritance.</p>
 */
class PageAccessGuardModuleRoleTest {

    private static final String WIKI_SEITE = "/wiki-projekte.xhtml";

    /** Wiki module as on the real classpath: root with moduleId, children with their own roles. */
    private static List<MenuItemImpl> wikiMenue(Set<String> benutzerRollen) {
        MenuItemImpl wurzel = PageAccessGuardTestFactory.menu("Wiki", "", "wiki.html", benutzerRollen,
                "USER", "ADMIN", "ROOT");
        wurzel.setModuleId("wiki");
        MenuItemImpl projekte = PageAccessGuardTestFactory.menu("Projekte", "Wiki", "wiki-projekte.html",
                benutzerRollen, "USER", "ADMIN", "ROOT");
        MenuItemImpl kontakte = PageAccessGuardTestFactory.menu("Kontakte", "", "kontakte.html", benutzerRollen,
                "USER", "ADMIN", "ROOT");
        kontakte.setModuleId("kontakte");
        return List.of(wurzel, projekte, kontakte);
    }

    private static PageAccessGuardService guard(Map<String, String> konfiguration, Set<String> benutzerRollen) {
        List<MenuItemImpl> menues = wikiMenue(benutzerRollen);
        ModuleRoleProperties properties = new ModuleRoleProperties();
        properties.setModuleRoles(new LinkedHashMap<>(konfiguration));
        new ModuleRoleService(null, properties).resolve(menues);
        return PageAccessGuardTestFactory.strictMitMenues(menues.toArray(new MenuItemImpl[0]));
    }

    @Test
    void direktaufrufOhneModulRolleWirdVerweigert() {
        assertFalse(guard(Map.of("wiki", "wiki"), Set.of("USER")).hasAccessToView(WIKI_SEITE));
    }

    @Test
    void direktaufrufMitModulRolleIstErlaubt() {
        assertTrue(guard(Map.of("wiki", "wiki"), Set.of("USER", "WIKI")).hasAccessToView(WIKI_SEITE));
    }

    @Test
    void adminUndRootKommenImmerDurch() {
        assertTrue(guard(Map.of("wiki", "wiki"), Set.of("ADMIN")).hasAccessToView(WIKI_SEITE));
        assertTrue(guard(Map.of("wiki", "wiki"), Set.of("ROOT")).hasAccessToView(WIKI_SEITE));
    }

    @Test
    void auchDieModulWurzelseiteIstGesperrt() {
        assertFalse(guard(Map.of("wiki", "wiki"), Set.of("USER")).hasAccessToView("/wiki.xhtml"));
    }

    @Test
    void unkonfigurierteModuleBleibenErreichbar() {
        PageAccessGuardService guard = guard(Map.of("wiki", "wiki"), Set.of("USER"));

        assertTrue(guard.hasAccessToView("/kontakte.xhtml"), "anderes Modul unveraendert offen");
    }

    @Test
    void ohneKonfigurationBleibtAllesErreichbar() {
        PageAccessGuardService guard = guard(Map.of(), Set.of("USER"));

        assertTrue(guard.hasAccessToView(WIKI_SEITE));
        assertTrue(guard.hasAccessToView("/kontakte.xhtml"));
    }
}

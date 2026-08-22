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
 * Konfigurierbare Modul-Rollen muessen auch den <em>Direktaufruf</em> der Seiten verweigern, nicht
 * nur den Menuepunkt ausblenden.
 *
 * <p>Der Nachweis ist nicht trivial: Die Kinder eines Modul-Menues deklarieren typischerweise
 * selbst {@code roles = {USER, ADMIN, ROOT}} (so z.B. das Wiki-Modul). Damit greift die
 * Eltern-Vererbung des Guards ausdruecklich <em>nicht</em>
 * ({@code PageAccessGuardService#istSichtbarMitEltern}: eigene {@code roles} sind abschliessend).
 * Der Schutz kann also nur wirken, weil die Modul-Rolle Teil von {@link MenuItemImpl#isOn()}
 * selbst ist — und {@code isOn()} wird vor der Vererbung geprueft.</p>
 */
class PageAccessGuardModuleRoleTest {

    private static final String WIKI_SEITE = "/wiki-projekte.xhtml";

    /** Wiki-Modul wie im echten Klassenpfad: Wurzel mit moduleId, Kinder mit eigenen roles. */
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

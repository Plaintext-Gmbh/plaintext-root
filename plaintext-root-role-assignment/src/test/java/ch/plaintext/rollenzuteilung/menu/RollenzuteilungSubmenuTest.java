/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.menu;

import ch.plaintext.boot.menu.MenuAnnotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RollenzuteilungSubmenu Tests")
class RollenzuteilungSubmenuTest {

    @Test
    @DisplayName("Should be annotated with @MenuAnnotation")
    void shouldBeAnnotatedWithMenuAnnotation() {
        assertTrue(RollenzuteilungSubmenu.class.isAnnotationPresent(MenuAnnotation.class));
    }

    @Test
    @DisplayName("Should have correct title")
    void shouldHaveCorrectTitle() {
        MenuAnnotation annotation = RollenzuteilungSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertEquals("Rollenzuteilung", annotation.title());
    }

    @Test
    @DisplayName("Should have correct link")
    void shouldHaveCorrectLink() {
        MenuAnnotation annotation = RollenzuteilungSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertEquals("rollenzuteilung.html", annotation.link());
    }

    @Test
    @DisplayName("Should have Admin as parent")
    void shouldHaveAdminAsParent() {
        MenuAnnotation annotation = RollenzuteilungSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertEquals("Admin", annotation.parent());
    }

    @Test
    @DisplayName("Should have order 5")
    void shouldHaveOrder5() {
        MenuAnnotation annotation = RollenzuteilungSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertEquals(5, annotation.order());
    }

    @Test
    @DisplayName("Should have pi pi-users icon")
    void shouldHaveCorrectIcon() {
        MenuAnnotation annotation = RollenzuteilungSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertEquals("pi pi-users", annotation.icon());
    }

    /**
     * SECURITY (Karte 308, H4): Vorher stand hier {@code assertEquals(0, annotation.roles().length)}.
     * Ohne eigene {@code roles} prueft {@code MenuItemImpl.isOn()} nichts — und weil der
     * {@code PageAccessGuardService} nur {@code isOn()} DIESES Eintrags auswertet und nicht die des
     * Elternmenues „Admin", war {@code /rollenzuteilung.html} per Direkt-URL fuer jeden
     * eingeloggten USER offen (Rollen vergeben!). Im gerenderten Menue war der Punkt nur unter dem
     * ADMIN-Elternmenue sichtbar, die Darstellung aendert sich also nicht.
     */
    @Test
    @DisplayName("Should be restricted to ADMIN/ROOT")
    void shouldBeRestrictedToAdminAndRoot() {
        MenuAnnotation annotation = RollenzuteilungSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertArrayEquals(new String[]{"ADMIN", "ROOT"}, annotation.roles());
    }

    @Test
    @DisplayName("Should be instantiable")
    void shouldBeInstantiable() {
        assertDoesNotThrow(RollenzuteilungSubmenu::new);
    }
}

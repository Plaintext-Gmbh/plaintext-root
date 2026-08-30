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
     * SECURITY (Card 308, H4): this used to read {@code assertEquals(0, annotation.roles().length)}.
     * Without its own {@code roles} the {@code MenuItemImpl.isOn()} check does nothing — and because
     * the {@code PageAccessGuardService} only evaluates {@code isOn()} of THIS entry and not the one
     * of the "Admin" parent menu, {@code /rollenzuteilung.html} was reachable by direct URL for
     * every logged-in USER (granting roles!). In the rendered menu the item was only visible below
     * the ADMIN parent menu, so its appearance does not change.
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

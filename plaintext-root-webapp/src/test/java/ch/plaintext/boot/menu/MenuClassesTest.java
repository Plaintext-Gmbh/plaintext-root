/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.boot.performance.PerformanceMenu;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for menu annotation classes - AdminSuperMenu, DebugMenu, HomeMenu,
 * RootSuperMenu, PerformanceMenu.
 */
class MenuClassesTest {

    @Test
    void adminSuperMenu_shouldHaveCorrectAnnotation() {
        MenuAnnotation annotation = AdminSuperMenu.class.getAnnotation(MenuAnnotation.class);

        assertNotNull(annotation);
        assertEquals("Admin", annotation.title());
        assertEquals("index.html", annotation.link());
        assertEquals("", annotation.parent());
        assertEquals(2, annotation.order());
        assertEquals("pi pi-cog", annotation.icon());
        assertArrayEquals(new String[]{"ADMIN"}, annotation.roles());
    }

    @Test
    void debugMenu_shouldHaveCorrectAnnotation() {
        MenuAnnotation annotation = DebugMenu.class.getAnnotation(MenuAnnotation.class);

        assertNotNull(annotation);
        assertEquals("Debug", annotation.title());
        assertEquals("debug.html", annotation.link());
        assertEquals("Admin", annotation.parent());
        assertEquals(50, annotation.order());
        assertEquals("pi pi-wrench", annotation.icon());
        assertArrayEquals(new String[]{"ADMIN"}, annotation.roles());
    }

    @Test
    void homeMenu_shouldHaveCorrectAnnotation() {
        MenuAnnotation annotation = HomeMenu.class.getAnnotation(MenuAnnotation.class);

        assertNotNull(annotation);
        assertEquals("Home", annotation.title());
        assertEquals("index.html", annotation.link());
        assertEquals(10, annotation.order());
        assertEquals("pi pi-home", annotation.icon());
    }

    @Test
    void rootSuperMenu_shouldHaveCorrectAnnotation() {
        MenuAnnotation annotation = RootSuperMenu.class.getAnnotation(MenuAnnotation.class);

        assertNotNull(annotation);
        assertEquals("Root", annotation.title());
        assertEquals("index.html", annotation.link());
        assertEquals("", annotation.parent());
        assertEquals(1, annotation.order());
        assertEquals("pi pi-prime", annotation.icon());
        assertArrayEquals(new String[]{"ROOT"}, annotation.roles());
    }

    @Test
    void performanceMenu_shouldHaveCorrectAnnotation() {
        MenuAnnotation annotation = PerformanceMenu.class.getAnnotation(MenuAnnotation.class);

        assertNotNull(annotation);
        assertEquals("Performance", annotation.title());
        assertEquals("performance.html", annotation.link());
        assertEquals("Root", annotation.parent());
        assertEquals(1, annotation.order());
        assertEquals("pi pi-prime", annotation.icon());
        assertArrayEquals(new String[]{"ROOT"}, annotation.roles());
    }

    @Test
    void adminSuperMenu_shouldBeInstantiable() {
        assertDoesNotThrow(AdminSuperMenu::new);
    }

    @Test
    void debugMenu_shouldBeInstantiable() {
        assertDoesNotThrow(DebugMenu::new);
    }

    @Test
    void homeMenu_shouldBeInstantiable() {
        assertDoesNotThrow(HomeMenu::new);
    }

    @Test
    void rootSuperMenu_shouldBeInstantiable() {
        assertDoesNotThrow(RootSuperMenu::new);
    }

    @Test
    void performanceMenu_shouldBeInstantiable() {
        assertDoesNotThrow(PerformanceMenu::new);
    }
}

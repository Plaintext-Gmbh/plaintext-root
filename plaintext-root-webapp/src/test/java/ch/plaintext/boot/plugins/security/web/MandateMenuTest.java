/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.boot.menu.MenuAnnotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MandateMenu annotation values.
 */
class MandateMenuTest {

    @Test
    void mandateMenu_shouldHaveCorrectAnnotation() {
        MenuAnnotation annotation = MandateMenu.class.getAnnotation(MenuAnnotation.class);

        assertNotNull(annotation);
        assertEquals("Mandate", annotation.title());
        assertEquals("mandate.html", annotation.link());
        assertEquals("Root", annotation.parent());
        assertEquals(61, annotation.order());
        assertEquals("pi pi-list", annotation.icon());
        assertArrayEquals(new String[]{"ROOT"}, annotation.roles());
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.menu;

import ch.plaintext.boot.menu.MenuAnnotation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionsSubmenuTest {

    @Test
    void classIsAnnotatedWithMenuAnnotation() {
        assertThat(SessionsSubmenu.class.isAnnotationPresent(MenuAnnotation.class)).isTrue();
    }

    @Test
    void menuAnnotationHasCorrectTitle() {
        MenuAnnotation annotation = SessionsSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation.title()).isEqualTo("Sessions");
    }

    @Test
    void menuAnnotationHasCorrectLink() {
        MenuAnnotation annotation = SessionsSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation.link()).isEqualTo("sessions.html");
    }

    @Test
    void menuAnnotationHasCorrectParent() {
        MenuAnnotation annotation = SessionsSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation.parent()).isEqualTo("Admin");
    }

    @Test
    void menuAnnotationHasCorrectOrder() {
        MenuAnnotation annotation = SessionsSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation.order()).isEqualTo(4);
    }

    @Test
    void menuAnnotationHasCorrectIcon() {
        MenuAnnotation annotation = SessionsSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation.icon()).isEqualTo("pi pi-users");
    }

    @Test
    void canBeInstantiated() {
        SessionsSubmenu submenu = new SessionsSubmenu();
        assertThat(submenu).isNotNull();
    }
}

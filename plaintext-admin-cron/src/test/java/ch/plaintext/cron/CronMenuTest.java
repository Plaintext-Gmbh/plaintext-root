/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.boot.menu.MenuAnnotation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CronMenuTest {

    @Test
    void classCanBeInstantiated() {
        CronMenu menu = new CronMenu();
        assertThat(menu).isNotNull();
    }

    @Test
    void hasMenuAnnotation() {
        assertThat(CronMenu.class.isAnnotationPresent(MenuAnnotation.class)).isTrue();
    }

    @Test
    void menuAnnotationHasCorrectTitle() {
        MenuAnnotation annotation = CronMenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation.title()).isEqualTo("Cron");
    }

    @Test
    void menuAnnotationHasCorrectLink() {
        MenuAnnotation annotation = CronMenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation.link()).isEqualTo("cron.html");
    }

    @Test
    void menuAnnotationHasCorrectOrder() {
        MenuAnnotation annotation = CronMenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation.order()).isEqualTo(10);
    }

    @Test
    void menuAnnotationHasCorrectParent() {
        MenuAnnotation annotation = CronMenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation.parent()).isEqualTo("Admin");
    }

    @Test
    void menuAnnotationHasCorrectIcon() {
        MenuAnnotation annotation = CronMenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation.icon()).isEqualTo("pi pi-calendar-times");
    }

    @Test
    void menuAnnotationHasCorrectRoles() {
        MenuAnnotation annotation = CronMenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation.roles()).containsExactly("ADMIN");
    }
}

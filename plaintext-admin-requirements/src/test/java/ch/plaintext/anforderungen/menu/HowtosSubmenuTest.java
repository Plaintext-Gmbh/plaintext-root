/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.menu;

import ch.plaintext.boot.menu.MenuAnnotation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HowtosSubmenuTest {

    @Test
    void classHasMenuAnnotation() {
        MenuAnnotation annotation = HowtosSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.title()).isEqualTo("Howtos");
        assertThat(annotation.link()).isEqualTo("howtos.html");
        assertThat(annotation.parent()).isEqualTo("Anforderungen");
        assertThat(annotation.order()).isEqualTo(2);
        assertThat(annotation.icon()).isEqualTo("pi pi-book");
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.menu;

import ch.plaintext.boot.menu.MenuAnnotation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SetupSubmenuTest {

    @Test
    void classHasMenuAnnotation() {
        MenuAnnotation annotation = SetupSubmenu.class.getAnnotation(MenuAnnotation.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.title()).isEqualTo("Setup");
        assertThat(annotation.link()).isEqualTo("setup.html");
        assertThat(annotation.parent()).isEqualTo("Root");
        assertThat(annotation.order()).isEqualTo(2);
        assertThat(annotation.icon()).isEqualTo("pi pi-cog");
    }
}

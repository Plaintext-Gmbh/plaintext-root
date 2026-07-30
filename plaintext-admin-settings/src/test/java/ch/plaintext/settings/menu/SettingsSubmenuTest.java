/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.menu;

import ch.plaintext.boot.menu.MenuAnnotation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsSubmenuTest {

    @Test
    void classIsAnnotatedWithMenuAnnotation() {
        MenuAnnotation annotation = SettingsSubmenu.class.getAnnotation(MenuAnnotation.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.title()).isEqualTo("Settings");
        assertThat(annotation.link()).isEqualTo("settings.html");
        assertThat(annotation.parent()).isEqualTo("Root");
        assertThat(annotation.order()).isEqualTo(1);
        assertThat(annotation.icon()).isEqualTo("pi pi-cog");
    }

    @Test
    void canBeInstantiated() {
        SettingsSubmenu submenu = new SettingsSubmenu();
        assertThat(submenu).isNotNull();
    }
}

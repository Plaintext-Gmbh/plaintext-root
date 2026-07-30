/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BrandingLogoTest {

    @Test
    void noArgsConstructorCreatesEmptyInstance() {
        BrandingLogo logo = new BrandingLogo();
        assertThat(logo.getId()).isNull();
        assertThat(logo.getMandat()).isNull();
        assertThat(logo.getTheme()).isNull();
        assertThat(logo.getLogoWidth()).isEqualTo(180);
        assertThat(logo.getLogoHeight()).isEqualTo(40);
    }

    @Test
    void allArgsConstructorSetsFields() {
        LocalDateTime now = LocalDateTime.now();
        BrandingLogo logo = new BrandingLogo(
                1L, "mandatA", "light", "base64data", "image/png",
                "logo.png", 200, 50, now, "creator", now, "modifier"
        );

        assertThat(logo.getId()).isEqualTo(1L);
        assertThat(logo.getMandat()).isEqualTo("mandatA");
        assertThat(logo.getTheme()).isEqualTo("light");
        assertThat(logo.getImageData()).isEqualTo("base64data");
        assertThat(logo.getContentType()).isEqualTo("image/png");
        assertThat(logo.getFileName()).isEqualTo("logo.png");
        assertThat(logo.getLogoWidth()).isEqualTo(200);
        assertThat(logo.getLogoHeight()).isEqualTo(50);
    }

    @Test
    void defaultWidthIs180() {
        BrandingLogo logo = new BrandingLogo();
        assertThat(logo.getLogoWidth()).isEqualTo(180);
    }

    @Test
    void defaultHeightIs40() {
        BrandingLogo logo = new BrandingLogo();
        assertThat(logo.getLogoHeight()).isEqualTo(40);
    }

    @Test
    void equalsAndHashCodeWork() {
        BrandingLogo l1 = new BrandingLogo();
        l1.setId(1L);
        l1.setMandat("m");
        l1.setTheme("light");

        BrandingLogo l2 = new BrandingLogo();
        l2.setId(1L);
        l2.setMandat("m");
        l2.setTheme("light");

        assertThat(l1).isEqualTo(l2);
        assertThat(l1.hashCode()).isEqualTo(l2.hashCode());
    }
}

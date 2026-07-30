/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SettingTest {

    @Test
    void noArgsConstructorCreatesEmptyEntity() {
        Setting setting = new Setting();
        assertThat(setting.getKey()).isNull();
        assertThat(setting.getMandat()).isNull();
        assertThat(setting.getValue()).isNull();
        assertThat(setting.getValueType()).isNull();
        assertThat(setting.getDescription()).isNull();
    }

    @Test
    void allArgsConstructorPopulatesAllFields() {
        LocalDateTime now = LocalDateTime.now();
        Setting setting = new Setting(
                "mail.smtp.host", "mandatA", "smtp.example.com",
                "STRING", "SMTP host", now, "admin", now, "admin"
        );

        assertThat(setting.getKey()).isEqualTo("mail.smtp.host");
        assertThat(setting.getMandat()).isEqualTo("mandatA");
        assertThat(setting.getValue()).isEqualTo("smtp.example.com");
        assertThat(setting.getValueType()).isEqualTo("STRING");
        assertThat(setting.getDescription()).isEqualTo("SMTP host");
    }

    @Test
    void getParentKeyReturnsParentForHierarchicalKey() {
        Setting setting = new Setting();
        setting.setKey("mail.smtp.host");

        assertThat(setting.getParentKey()).isEqualTo("mail.smtp");
    }

    @Test
    void getParentKeyReturnsNullForTopLevelKey() {
        Setting setting = new Setting();
        setting.setKey("toplevel");

        assertThat(setting.getParentKey()).isNull();
    }

    @Test
    void getParentKeyReturnsNullForNullKey() {
        Setting setting = new Setting();
        setting.setKey(null);

        assertThat(setting.getParentKey()).isNull();
    }

    @Test
    void getSimpleNameReturnsLastPart() {
        Setting setting = new Setting();
        setting.setKey("mail.smtp.host");

        assertThat(setting.getSimpleName()).isEqualTo("host");
    }

    @Test
    void getSimpleNameReturnsFullKeyWhenNoDots() {
        Setting setting = new Setting();
        setting.setKey("toplevel");

        assertThat(setting.getSimpleName()).isEqualTo("toplevel");
    }

    @Test
    void getSimpleNameReturnsNullForNullKey() {
        Setting setting = new Setting();
        setting.setKey(null);

        assertThat(setting.getSimpleName()).isNull();
    }

    @Test
    void getLevelReturnsCorrectDepth() {
        Setting setting = new Setting();

        setting.setKey("mail.smtp.host");
        assertThat(setting.getLevel()).isEqualTo(3);

        setting.setKey("mail.smtp");
        assertThat(setting.getLevel()).isEqualTo(2);

        setting.setKey("mail");
        assertThat(setting.getLevel()).isEqualTo(1);
    }

    @Test
    void getLevelReturnsZeroForNullKey() {
        Setting setting = new Setting();
        setting.setKey(null);

        assertThat(setting.getLevel()).isZero();
    }

    @Test
    void equalsAndHashCodeWorkForSameValues() {
        LocalDateTime now = LocalDateTime.now();
        Setting s1 = new Setting("key", "m", "v", "STRING", "d", now, "a", now, "a");
        Setting s2 = new Setting("key", "m", "v", "STRING", "d", now, "a", now, "a");

        assertThat(s1).isEqualTo(s2);
        assertThat(s1.hashCode()).isEqualTo(s2.hashCode());
    }

    @Test
    void getParentKeyForTwoLevelKey() {
        Setting setting = new Setting();
        setting.setKey("app.name");

        assertThat(setting.getParentKey()).isEqualTo("app");
    }
}

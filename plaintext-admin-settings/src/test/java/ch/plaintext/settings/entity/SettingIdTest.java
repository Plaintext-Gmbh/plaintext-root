/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingIdTest {

    @Test
    void noArgsConstructorCreatesEmptyId() {
        SettingId id = new SettingId();
        assertThat(id.getKey()).isNull();
        assertThat(id.getMandat()).isNull();
    }

    @Test
    void allArgsConstructorSetsFields() {
        SettingId id = new SettingId("mail.host", "mandatA");
        assertThat(id.getKey()).isEqualTo("mail.host");
        assertThat(id.getMandat()).isEqualTo("mandatA");
    }

    @Test
    void equalsAndHashCodeWorkForSameValues() {
        SettingId id1 = new SettingId("mail.host", "mandatA");
        SettingId id2 = new SettingId("mail.host", "mandatA");

        assertThat(id1).isEqualTo(id2);
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }

    @Test
    void equalsReturnsFalseForDifferentKeys() {
        SettingId id1 = new SettingId("mail.host", "mandatA");
        SettingId id2 = new SettingId("mail.port", "mandatA");

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void equalsReturnsFalseForDifferentMandat() {
        SettingId id1 = new SettingId("mail.host", "mandatA");
        SettingId id2 = new SettingId("mail.host", "mandatB");

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void settersWork() {
        SettingId id = new SettingId();
        id.setKey("app.name");
        id.setMandat("test");

        assertThat(id.getKey()).isEqualTo("app.name");
        assertThat(id.getMandat()).isEqualTo("test");
    }
}

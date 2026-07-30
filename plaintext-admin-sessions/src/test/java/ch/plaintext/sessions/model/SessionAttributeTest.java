/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAttributeTest {

    @Test
    void constructorSetsFieldsCorrectly() {
        SessionAttribute attr = new SessionAttribute("testName", "testValue", 512);

        assertThat(attr.getName()).isEqualTo("testName");
        assertThat(attr.getValue()).isEqualTo("testValue");
        assertThat(attr.getType()).isEqualTo("String");
        assertThat(attr.getSizeInBytes()).isEqualTo(512);
        assertThat(attr.getFormattedSize()).isEqualTo("512 B");
    }

    @Test
    void constructorHandlesNullValue() {
        SessionAttribute attr = new SessionAttribute("key", null, 0);

        assertThat(attr.getType()).isEqualTo("null");
        assertThat(attr.getSizeInBytes()).isZero();
        assertThat(attr.getFormattedSize()).isEqualTo("0 B");
    }

    @Test
    void formattedSizeShowsBytes() {
        SessionAttribute attr = new SessionAttribute("key", "val", 100);
        assertThat(attr.getFormattedSize()).isEqualTo("100 B");
    }

    @Test
    void formattedSizeShowsKilobytes() {
        SessionAttribute attr = new SessionAttribute("key", "val", 2048);
        assertThat(attr.getFormattedSize()).isEqualTo("2.00 KB");
    }

    @Test
    void formattedSizeShowsMegabytes() {
        SessionAttribute attr = new SessionAttribute("key", "val", 2 * 1024 * 1024);
        assertThat(attr.getFormattedSize()).isEqualTo("2.00 MB");
    }

    @Test
    void typeReflectsActualObjectType() {
        SessionAttribute intAttr = new SessionAttribute("num", 42, 4);
        assertThat(intAttr.getType()).isEqualTo("Integer");

        SessionAttribute listAttr = new SessionAttribute("list", java.util.List.of(), 10);
        assertThat(listAttr.getType()).contains("List");
    }

    @Test
    void equalsAndHashCodeWorkCorrectly() {
        SessionAttribute a1 = new SessionAttribute("key", "val", 100);
        SessionAttribute a2 = new SessionAttribute("key", "val", 100);

        assertThat(a1).isEqualTo(a2);
        assertThat(a1.hashCode()).isEqualTo(a2.hashCode());
    }
}

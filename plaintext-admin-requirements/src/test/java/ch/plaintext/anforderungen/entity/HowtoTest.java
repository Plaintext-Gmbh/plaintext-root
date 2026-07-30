/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HowtoTest {

    @Test
    void noArgsConstructorCreatesEmptyInstance() {
        Howto howto = new Howto();
        assertThat(howto.getId()).isNull();
        assertThat(howto.getActive()).isTrue();
    }

    @Test
    void allArgsConstructorSetsFields() {
        LocalDateTime now = LocalDateTime.now();
        Howto howto = new Howto(1L, "mandatA", "Build Guide", "Run mvn", "example", true, now, now);

        assertThat(howto.getId()).isEqualTo(1L);
        assertThat(howto.getMandat()).isEqualTo("mandatA");
        assertThat(howto.getName()).isEqualTo("Build Guide");
        assertThat(howto.getText()).isEqualTo("Run mvn");
        assertThat(howto.getBeispiel()).isEqualTo("example");
        assertThat(howto.getActive()).isTrue();
    }

    @Test
    void defaultActiveIsTrue() {
        Howto howto = new Howto();
        assertThat(howto.getActive()).isTrue();
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintTemplateTest {

    @Test
    void noArgsConstructorCreatesEmptyInstance() {
        ConstraintTemplate template = new ConstraintTemplate();
        assertThat(template.getId()).isNull();
        assertThat(template.getTitel()).isNull();
    }

    @Test
    void allArgsConstructorSetsFields() {
        LocalDateTime now = LocalDateTime.now();
        ConstraintTemplate template = new ConstraintTemplate(
                1L, "mandatA", "Java Rules", "Description",
                "Use Java 25", now, "creator", now, "modifier"
        );

        assertThat(template.getId()).isEqualTo(1L);
        assertThat(template.getMandat()).isEqualTo("mandatA");
        assertThat(template.getTitel()).isEqualTo("Java Rules");
        assertThat(template.getBeschreibung()).isEqualTo("Description");
        assertThat(template.getConstraintsContent()).isEqualTo("Use Java 25");
    }
}

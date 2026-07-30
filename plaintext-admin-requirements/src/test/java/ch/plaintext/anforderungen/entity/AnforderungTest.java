/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AnforderungTest {

    @Test
    void noArgsConstructorCreatesEmptyInstance() {
        Anforderung anf = new Anforderung();
        assertThat(anf.getId()).isNull();
        assertThat(anf.getTitel()).isNull();
        assertThat(anf.getWiederkehrend()).isFalse();
        assertThat(anf.getRequiresUserAnswer()).isFalse();
    }

    @Test
    void allArgsConstructorSetsAllFields() {
        LocalDateTime now = LocalDateTime.now();
        ConstraintTemplate ct = new ConstraintTemplate();
        Anforderung anf = new Anforderung(
                1L, "mandatA", "Title", "Desc", "OFFEN", "HOCH",
                "Bug", "user1", true, 7, now, now, "creator",
                now, "modifier", now, now, "summary", "answer",
                false, "mod1", "feature/{id}", "cycle info",
                1L, ct, "1,2"
        );

        assertThat(anf.getId()).isEqualTo(1L);
        assertThat(anf.getMandat()).isEqualTo("mandatA");
        assertThat(anf.getTitel()).isEqualTo("Title");
        assertThat(anf.getBeschreibung()).isEqualTo("Desc");
        assertThat(anf.getStatus()).isEqualTo("OFFEN");
        assertThat(anf.getPriority()).isEqualTo("HOCH");
        assertThat(anf.getKategorie()).isEqualTo("Bug");
        assertThat(anf.getErsteller()).isEqualTo("user1");
        assertThat(anf.getWiederkehrend()).isTrue();
        assertThat(anf.getWiederkehrendTage()).isEqualTo(7);
        assertThat(anf.getConstraintTemplateId()).isEqualTo(1L);
        assertThat(anf.getHowtoIds()).isEqualTo("1,2");
    }

    @Test
    void defaultValuesAreCorrect() {
        Anforderung anf = new Anforderung();
        assertThat(anf.getWiederkehrend()).isFalse();
        assertThat(anf.getRequiresUserAnswer()).isFalse();
    }

    @Test
    void equalsAndHashCodeWorkWithLombok() {
        Anforderung a1 = new Anforderung();
        a1.setId(1L);
        a1.setTitel("Test");

        Anforderung a2 = new Anforderung();
        a2.setId(1L);
        a2.setTitel("Test");

        assertThat(a1).isEqualTo(a2);
        assertThat(a1.hashCode()).isEqualTo(a2.hashCode());
    }

    @Test
    void toStringDoesNotThrow() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setTitel("Test");
        assertThat(anf.toString()).contains("Test");
    }
}

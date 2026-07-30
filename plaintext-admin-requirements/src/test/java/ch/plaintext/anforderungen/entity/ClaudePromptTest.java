/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudePromptTest {

    @Test
    void noArgsConstructorCreatesEmptyInstance() {
        ClaudePrompt prompt = new ClaudePrompt();
        assertThat(prompt.getId()).isNull();
        assertThat(prompt.getRetryCount()).isZero();
    }

    @Test
    void allArgsConstructorSetsAllFields() {
        LocalDateTime now = LocalDateTime.now();
        ClaudePrompt prompt = new ClaudePrompt(
                1L, "00001", 42L, "mandatA", "PENDING", "prompt text",
                now, now, now, now, 2, "error msg", "/tmp/lock"
        );

        assertThat(prompt.getId()).isEqualTo(1L);
        assertThat(prompt.getPromptNumber()).isEqualTo("00001");
        assertThat(prompt.getAnforderungId()).isEqualTo(42L);
        assertThat(prompt.getMandat()).isEqualTo("mandatA");
        assertThat(prompt.getStatus()).isEqualTo("PENDING");
        assertThat(prompt.getPromptText()).isEqualTo("prompt text");
        assertThat(prompt.getRetryCount()).isEqualTo(2);
        assertThat(prompt.getErrorMessage()).isEqualTo("error msg");
        assertThat(prompt.getLockfilePath()).isEqualTo("/tmp/lock");
    }

    @Test
    void defaultRetryCountIsZero() {
        ClaudePrompt prompt = new ClaudePrompt();
        assertThat(prompt.getRetryCount()).isZero();
    }
}

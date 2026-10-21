/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude;

import ch.plaintext.modules.ModulApi;

import java.util.List;
import java.util.Objects;

/**
 * A request to Claude.
 *
 * <p>No temperature: the current models (Opus 5.5, Sonnet 5.5, Haiku 5.5) reject non-default
 * sampling parameters with HTTP 400.
 *
 * @param quelle      calling module, used for the statistics (required)
 * @param modell      model id, or {@code null} for {@code plaintext.claude.default-model}
 * @param system      system prompt, or {@code null}
 * @param nachrichten the conversation, alternating user and assistant, starting with the user
 * @param maxTokens   upper limit of output tokens; {@code 0} or less means the default (1024)
 */
@ModulApi(art = ModulApi.Art.DTO)
public record ClaudeAnfrage(String quelle, String modell, String system, List<Nachricht> nachrichten, int maxTokens) {

    public ClaudeAnfrage {
        Objects.requireNonNull(quelle, "quelle");
        if (nachrichten == null || nachrichten.isEmpty()) {
            throw new IllegalArgumentException("nachrichten must not be empty");
        }
        nachrichten = List.copyOf(nachrichten);
    }

    /** One user prompt, default model, default token limit. */
    public static ClaudeAnfrage einfach(String quelle, String prompt) {
        return new ClaudeAnfrage(quelle, null, null, List.of(Nachricht.benutzer(prompt)), 0);
    }

    /**
     * One message of the conversation.
     *
     * @param rolle {@code "user"} or {@code "assistant"}
     * @param text  the text
     */
    public record Nachricht(String rolle, String text) {

        /** A message from the user. */
        public static Nachricht benutzer(String text) {
            return new Nachricht("user", text);
        }

        /** An earlier answer of the assistant. */
        public static Nachricht assistent(String text) {
            return new Nachricht("assistant", text);
        }
    }
}

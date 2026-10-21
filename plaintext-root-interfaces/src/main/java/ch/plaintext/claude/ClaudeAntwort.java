/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude;

import ch.plaintext.modules.ModulApi;

/**
 * Claude's answer to a {@link ClaudeAnfrage}.
 *
 * @param text         the text blocks of the answer, concatenated
 * @param modell       the model that answered
 * @param stopGrund    {@code end_turn}, {@code max_tokens}, {@code refusal}, ... — check it before trusting the text
 * @param inputTokens  uncached input tokens
 * @param outputTokens output tokens
 * @param cacheTokens  cache creation plus cache read input tokens
 * @param requestId    Anthropic request id, for support requests
 */
@ModulApi(art = ModulApi.Art.DTO)
public record ClaudeAntwort(String text, String modell, String stopGrund, long inputTokens, long outputTokens,
                            long cacheTokens, String requestId) {
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude;

import ch.plaintext.modules.ModulApi;

/**
 * Sends a question to Claude (Anthropic Messages API) using the installation's API key, so that
 * modules can use the language model without knowing the key, the HTTP details or the retry rules.
 *
 * <p>Every call is counted per calling module ({@link ClaudeAnfrage#quelle()}), model and day; the
 * root page <i>Root → Claude API</i> shows that consumption next to the connection state and the
 * rate-limit headroom.
 *
 * <p>Implemented by {@code plaintext-admin-claude}. Consumers should bind it <b>optionally</b>
 * ({@code @Autowired(required = false)} or {@code ObjectProvider}), because an application may opt
 * the module out, and should check {@link #isKonfiguriert()} before offering a feature that needs it.
 */
@ModulApi(art = ModulApi.Art.SCHNITTSTELLE)
public interface ClaudeClient {

    /**
     * Whether an API key is configured. Without one every call fails with
     * {@link ClaudeException.Art#NICHT_KONFIGURIERT}.
     *
     * @return {@code true} if {@code plaintext.claude.api-key} is set
     */
    boolean isKonfiguriert();

    /**
     * Sends one request and waits for the complete answer (no streaming).
     *
     * @param anfrage the request; {@code quelle} names the calling module for the statistics
     * @return the answer text plus model, stop reason and token counts
     * @throws ClaudeException when the key is missing, rejected, the rate limit is exhausted after
     *                         the retries, the API is overloaded or the network fails
     */
    ClaudeAntwort frage(ClaudeAnfrage anfrage);

    /**
     * Convenience: one user prompt with the default model and default token limit.
     *
     * @param quelle calling module, e.g. {@code "plaintext-z-mail"}
     * @param prompt the user prompt
     * @return the answer text
     * @throws ClaudeException see {@link #frage(ClaudeAnfrage)}
     */
    default String frage(String quelle, String prompt) {
        return frage(ClaudeAnfrage.einfach(quelle, prompt)).text();
    }
}

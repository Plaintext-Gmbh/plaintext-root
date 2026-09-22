/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.service;

import ch.plaintext.boot.plugins.secret.EnvKeyAesGcmCrypto;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption/decryption for the webhook signing secrets (unlike a pure password hash
 * they must be recoverable in order to sign every outgoing request, hence encryption instead of
 * hashing).
 *
 * <p>Key handling, dev fallback, PROD fail-fast and the wire format live in
 * {@link EnvKeyAesGcmCrypto} (plaintext-root-common). Until card 1301 this class carried its own
 * copy of that hardening, which had already drifted: the copy lacked
 * {@link EnvKeyAesGcmCrypto#isDevFallback()}, so nothing could report that the webhook signing
 * secrets were sitting on a publicly computable key. The shared class is the stronger of the two
 * former copies.</p>
 *
 * <p>The case here is particularly delicate: a computable key yields readable signing secrets and
 * from those <b>valid signatures</b> for outgoing webhooks.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component
public class WebhookCrypto extends EnvKeyAesGcmCrypto {

    public WebhookCrypto(Environment environment) {
        this(isProduction(environment));
    }

    /** Test constructor: forces the dev fallback without a Spring context. */
    WebhookCrypto() {
        this(false);
    }

    private WebhookCrypto(boolean production) {
        super(production, "Webhook-Secret");
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.boot.plugins.secret.EnvKeyAesGcmCrypto;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption/decryption for locally stored secrets and the backend access token.
 *
 * <p>Key handling, dev fallback, PROD fail-fast and the wire format live in
 * {@link EnvKeyAesGcmCrypto} (plaintext-root-common). Until card 1301 they were a hand-kept copy
 * of {@code WebhookCrypto} — see the Javadoc there for why the copy is gone.</p>
 */
@Component
public class SecretCrypto extends EnvKeyAesGcmCrypto {

    public SecretCrypto(Environment environment) {
        this(isProduction(environment));
    }

    /** Test constructor: forces the dev fallback without a Spring context. */
    SecretCrypto() {
        this(false);
    }

    private SecretCrypto(boolean production) {
        super(production, "Secret");
    }
}

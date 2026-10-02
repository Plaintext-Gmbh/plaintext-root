/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.service;

import ch.plaintext.boot.plugins.secret.EnvKeyAesGcmCrypto;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Verschlüsselt die Sidecar-Tokens (Karte 1400) wie {@code SecretCrypto} und {@code WebhookCrypto}:
 * AES-256-GCM mit dem Schlüssel aus {@code PLAINTEXT_SECRET_KEY}, in PROD Pflicht.
 *
 * <p>Warum nicht die Root-Secrets: die sind ans Mandat gebunden und nur im Request-Kontext lesbar.
 * Ein Sidecar-Token wird aber auch im Cron und im Webhook gebraucht.</p>
 */
@Component
public class SidecarCrypto extends EnvKeyAesGcmCrypto {

    @org.springframework.beans.factory.annotation.Autowired
    public SidecarCrypto(Environment environment) {
        this(isProduction(environment));
    }

    /** Test-Konstruktor: Dev-Schlüssel ohne Spring-Kontext. */
    SidecarCrypto() {
        this(false);
    }

    private SidecarCrypto(boolean production) {
        super(production, "Sidecar-Token");
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.plugins.security.helpers.TotpSecretCrypto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Reicht das Schluesselmaterial fuer {@link TotpSecretCrypto} beim Start durch — eigener
 * Schluessel, sonst der remember-me-key. Hoechste Prioritaet, damit der Schluessel steht, bevor
 * der erste Benutzer geladen wird (PlaintextInitLoader).
 *
 * @author info@plaintext.ch
 * @since 1.636.0
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TotpSecretCryptoInitializer {

    public TotpSecretCryptoInitializer(PlaintextSecurityProperties properties) {
        String eigener = properties.getTotpEncryptionKey();
        String material = eigener != null && !eigener.isBlank() ? eigener : properties.getRememberMeKey();
        TotpSecretCrypto.configure(material);
        log.info("TOTP-Secrets: Verschluesselung {}", TotpSecretCrypto.isConfigured()
                ? (eigener != null && !eigener.isBlank() ? "aktiv (eigener Schluessel)" : "aktiv (remember-me-key)")
                : "AUS — kein Schluessel");
    }
}

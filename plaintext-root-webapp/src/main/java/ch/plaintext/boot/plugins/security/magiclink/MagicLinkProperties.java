/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.magiclink;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "plaintext.magiclink")
@Data
public class MagicLinkProperties {

    /**
     * Validity period of a magic link (default: 10 minutes).
     */
    private Duration tokenTtl = Duration.ofMinutes(10);

    /**
     * Public base URL for the sent link (e.g. {@code https://app.example.com}).
     * Empty = the own address from {@code EigeneAdresse} ({@code app.ownhost},
     * {@code plaintext.app.ownhost}, {@code plaintext.baseurl}). Never taken from the request
     * (host header injection, Karte 1501); with nothing configured no mail is sent.
     */
    private String publicBaseUrl = "";
}

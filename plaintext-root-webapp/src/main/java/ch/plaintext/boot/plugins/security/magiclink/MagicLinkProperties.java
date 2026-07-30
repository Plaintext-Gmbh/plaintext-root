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
     * Gueltigkeitsdauer eines Magic-Links (Default: 10 Minuten).
     */
    private Duration tokenTtl = Duration.ofMinutes(10);

    /**
     * Oeffentliche Basis-URL fuer den versendeten Link (z. B. {@code https://app.example.com}).
     * Sollte in jeder Umgebung konfiguriert sein. Leer = Fallback auf Scheme/Server-Name/Port des
     * eingehenden Requests – bewusst OHNE {@code X-Forwarded-*}-Header (Host-Header-Injection),
     * mit WARN-Log.
     */
    private String publicBaseUrl = "";
}

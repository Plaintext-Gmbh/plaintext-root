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
     * Should be configured in every environment. Empty = fallback to scheme/server name/port of the
     * incoming request - deliberately WITHOUT {@code X-Forwarded-*} headers (host header injection),
     * with a WARN log entry.
     */
    private String publicBaseUrl = "";
}

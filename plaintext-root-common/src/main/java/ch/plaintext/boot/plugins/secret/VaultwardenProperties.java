/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration of the Vaultwarden secret client (prefix {@code plaintext.vault}).
 *
 * <p>The bootstrap secrets ({@code email}, {@code masterPassword}, optionally
 * {@code clientId}/{@code clientSecret}) belong exclusively into the env resp. into
 * the NAS {@code _secrets} files — NEVER into git/code/logs.</p>
 *
 * <p>Example (env / vault.env):</p>
 * <pre>
 * PLAINTEXT_VAULT_ENABLED=true
 * PLAINTEXT_VAULT_EMAIL=service@example.org
 * PLAINTEXT_VAULT_MASTER_PASSWORD=...
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "plaintext.vault")
public class VaultwardenProperties {

    /** Master switch. When {@code false}, the entire client is inactive (fail-safe). */
    private boolean enabled = false;

    /** Base URL of the Vaultwarden instance. */
    private String url = "https://vault.plaintext.ch";

    /** Login email (a service account is recommended). */
    private String email;

    /** Master password of the (service) account. */
    private String masterPassword;

    /**
     * Alternative to {@link #masterPassword}: path to a file that contains the value
     * ({@code *_FILE} convention, Karte 942).
     *
     * <p>This keeps the secret out of the container environment, so it no longer shows up in
     * {@code printenv}, {@code docker inspect}, crash dumps or process listings. The file is read
     * in {@code VaultwardenSecretFiles} before the client is built; a trailing line break is
     * stripped. If the file is missing or empty, startup aborts — silently carrying on without a
     * bootstrap secret would be worse.</p>
     *
     * <p>If the file AND the variable are set, the variable wins (with a WARN in the log).</p>
     */
    private String masterPasswordFile;

    /** Like {@link #masterPasswordFile}, for {@link #clientId}. */
    private String clientIdFile;

    /** Like {@link #masterPasswordFile}, for {@link #clientSecret}. */
    private String clientSecretFile;

    /**
     * Optional personal API key {@code client_id} for the
     * {@code client_credentials} grant. If it is set (together with {@link #clientSecret}),
     * that grant is used instead of {@code password}.
     */
    private String clientId;

    /** Optional {@code client_secret} belonging to {@link #clientId}. */
    private String clientSecret;

    /**
     * User agent for EVERY request. An upstream WAF/CDN proxy frequently blocks
     * generic agents with {@code error code: 1010} — hence a browser UA.
     */
    private String userAgent =
            "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

    /** Cache lifetime of the decrypted items in seconds. */
    private int cacheTtlSeconds = 300;

    /** Lifetime of a login in minutes (re-login afterwards). */
    private int loginTtlMinutes = 30;

    /** HTTP timeout per request in seconds. */
    private int httpTimeoutSeconds = 30;

    /**
     * Device UUID (Bitwarden {@code deviceIdentifier}). Empty/{@code null} = derived STABLY and
     * deterministically from email + app name, so that restarts/redeploys do not count as a
     * "new device" (otherwise Vaultwarden sends a "New Device Logged In" mail on every login).
     * Only set it when a fixed UUID is wanted.
     */
    private String deviceIdentifier;

    /** Display name of the device in Vaultwarden ({@code deviceName}); the app name is appended. */
    private String deviceName = "plaintext";
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Konfiguration des Vaultwarden-Secret-Clients (Prefix {@code plaintext.vault}).
 *
 * <p>Die Bootstrap-Secrets ({@code email}, {@code masterPassword}, optional
 * {@code clientId}/{@code clientSecret}) gehoeren ausschliesslich in Env bzw. in
 * die NAS-{@code _secrets}-Dateien — NIEMALS in git/Code/Logs.</p>
 *
 * <p>Beispiel (Env / vault.env):</p>
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

    /** Master-Schalter. Ist {@code false}, ist der gesamte Client inaktiv (fail-safe). */
    private boolean enabled = false;

    /** Basis-URL der Vaultwarden-Instanz. */
    private String url = "https://vault.plaintext.ch";

    /** Login-E-Mail (Service-Account empfohlen). */
    private String email;

    /** Master-Passwort des (Service-)Accounts. */
    private String masterPassword;

    /**
     * Optionaler Personal-API-Key {@code client_id} fuer den
     * {@code client_credentials}-Grant. Ist er (samt {@link #clientSecret}) gesetzt,
     * wird dieser Grant statt {@code password} verwendet.
     */
    private String clientId;

    /** Optionales {@code client_secret} zum {@link #clientId}. */
    private String clientSecret;

    /**
     * User-Agent fuer JEDEN Request. Ein vorgelagerter WAF/CDN-Proxy blockt haeufig
     * generische Agents mit {@code error code: 1010} — daher ein Browser-UA.
     */
    private String userAgent =
            "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

    /** Cache-Lebensdauer der entschluesselten Items in Sekunden. */
    private int cacheTtlSeconds = 300;

    /** Lebensdauer eines Logins in Minuten (Re-Login danach). */
    private int loginTtlMinutes = 30;

    /** HTTP-Timeout je Request in Sekunden. */
    private int httpTimeoutSeconds = 30;

    /**
     * Geraete-UUID (Bitwarden {@code deviceIdentifier}). Leer/{@code null} = wird STABIL und
     * deterministisch aus Email + App-Name abgeleitet, damit Neustarts/Redeploys nicht als
     * „neues Geraet" gelten (sonst schickt Vaultwarden bei jedem Login eine „New Device Logged
     * In"-Mail). Nur setzen, wenn eine feste UUID gewuenscht ist.
     */
    private String deviceIdentifier;

    /** Anzeigename des Geraets in Vaultwarden ({@code deviceName}); der App-Name wird angehaengt. */
    private String deviceName = "plaintext";
}

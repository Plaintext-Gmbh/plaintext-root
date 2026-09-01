/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code vault:} property resolver — makes secrets from vault.example.org
 * (Vaultwarden) transparently available through {@code @Value} /
 * {@code @ConfigurationProperties}, analogous to Spring Cloud Vault.
 *
 * <p>This {@link EnvironmentPostProcessor} is registered through
 * {@code META-INF/spring.factories} (key
 * {@code org.springframework.boot.env.EnvironmentPostProcessor}). It hangs a
 * {@link VaultwardenPropertySource} into the environment IN FIRST POSITION. Every
 * property value — no matter which source it comes from (application.yml, env, ...) —
 * that carries the prefix {@code vault:} is transparently replaced by the
 * corresponding vault secret on access.</p>
 *
 * <h2>Three syntax forms</h2>
 * <ul>
 *   <li>{@code vault:<item>} &rarr; password of the login item</li>
 *   <li>{@code vault:<item>#username} &rarr; user name of the item</li>
 *   <li>{@code vault:<item>#field:<feldname>} &rarr; custom field</li>
 * </ul>
 *
 * <h2>Naming convention</h2>
 * <p>Items are ALWAYS named {@code <app>.<key>} (e.g. {@code app.jira-bit-admin},
 * {@code app.sciforma}, {@code guild.paperless-token}). If the item name does not
 * match {@code ^[a-z0-9-]+\.[a-z0-9-]+}, a WARN is logged — it is resolved
 * nonetheless.</p>
 *
 * <h2>Example (app.env)</h2>
 * <pre>
 * # Bootstrap of the vault client (NEVER in git/code):
 * PLAINTEXT_VAULT_ENABLED=true
 * PLAINTEXT_VAULT_EMAIL=service@example.org
 * PLAINTEXT_VAULT_MASTER_PASSWORD=...
 * PLAINTEXT_VAULT_URL=https://vault.example.org
 *
 * # Secrets as vault: references (item name = app.key):
 * PLAINTEXT_BUCHHALTUNG_PAPERLESS_TOKEN=vault:guild.paperless-token
 * ZEIT_JIRA_USER=vault:app.jira-bit-admin#username
 * ZEIT_JIRA_PASSWORD=vault:app.jira-bit-admin
 * ZEIT_SCIFORMA_API_KEY=vault:app.sciforma#field:api-key
 * </pre>
 *
 * <h2>Error behaviour</h2>
 * <p>If a {@code vault:} value cannot be resolved (vault disabled,
 * login error or item/field missing), the boot aborts FAIL-FAST with a
 * {@link VaultwardenPropertyResolutionException}. The message names only
 * property and item names — NEVER secret values or the master password.
 * Normal (non-{@code vault:}) values are passed through unchanged.</p>
 *
 * <p><b>The abort also wins against a default.</b> A
 * {@code @Value("$&#123;plaintext.foo.token:&#125;")} does NOT fall back to the empty default
 * on an unresolvable {@code vault:} reference — the default only applies when the property is
 * missing altogether, not when its resolution fails. That is intentional: an empty value would
 * look like a harmless configuration gap while in truth the app ran without its secret. Pinned
 * down in {@code VaultwardenFailFastVertragTest} (Karte 868).</p>
 */
@Slf4j
public class VaultwardenEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Karte 942: read the bootstrap secrets from files BEFORE anything builds the vault
        // client — otherwise the *_FILE setting takes effect too late and the login fails for no reason.
        VaultwardenSecretFiles.anwenden(environment);

        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(VaultwardenPropertySource.SOURCE_NAME)) {
            return; // idempotent
        }
        sources.addFirst(new VaultwardenPropertySource(environment));
        log.debug("Vault-Property-Resolver registriert (Source '{}' an erster Stelle)",
                VaultwardenPropertySource.SOURCE_NAME);

        // And NOW resolve all vault: references once and replace them in their source.
        // The lazy source alone is not enough: Spring Boot later hangs a source of its own in front
        // of it and passes the raw value through (see VaultwardenEagerResolution).
        //
        // Karte 995: der OpenBao-Client MUSS hier mitgegeben werden. Die Ein-Argument-Fassung des
        // Konstruktors setzt den bao-Supplier auf () -> null — und weil GENAU DIESER Resolver die
        // Referenzen im Betrieb aufloest (die traege PropertySource wird umgangen, siehe Kommentar
        // oben), scheiterte jede bao:-Referenz mit "OpenBao ist nicht konfiguriert", egal wie sie
        // konfiguriert war. Gemessen am 01.09.2026 an guild-INT.
        VaultwardenEagerResolution.resolveAll(environment,
                new VaultwardenValueResolver(() -> VaultwardenPropertySource.buildService(environment),
                        () -> VaultwardenPropertySource.buildBaoClient(environment), Thread::sleep));
    }

    /**
     * Run as late as possible, so that our source really is in first position
     * after all the other EPPs.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

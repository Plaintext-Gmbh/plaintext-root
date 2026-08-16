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
 * {@code vault:}-Property-Resolver — macht Secrets aus vault.example.org
 * (Vaultwarden) transparent ueber {@code @Value} / {@code @ConfigurationProperties}
 * verfuegbar, analog zu Spring Cloud Vault.
 *
 * <p>Registriert wird dieser {@link EnvironmentPostProcessor} ueber
 * {@code META-INF/spring.factories} (Schluessel
 * {@code org.springframework.boot.env.EnvironmentPostProcessor}). Er haengt eine
 * {@link VaultwardenPropertySource} AN ERSTER STELLE ins Environment. Jeder
 * Property-Wert — egal aus welcher Source (application.yml, Env, ...) — mit dem
 * Prefix {@code vault:} wird beim Zugriff transparent durch das entsprechende
 * Tresor-Secret ersetzt.</p>
 *
 * <h2>Drei Syntaxformen</h2>
 * <ul>
 *   <li>{@code vault:<item>} &rarr; Passwort des Login-Items</li>
 *   <li>{@code vault:<item>#username} &rarr; Benutzername des Items</li>
 *   <li>{@code vault:<item>#field:<feldname>} &rarr; benutzerdefiniertes Feld</li>
 * </ul>
 *
 * <h2>Namens-Konvention</h2>
 * <p>Items heissen IMMER {@code <app>.<key>} (z.B. {@code app.jira-bit-admin},
 * {@code app.sciforma}, {@code guild.paperless-token}). Passt der Item-Name nicht
 * auf {@code ^[a-z0-9-]+\.[a-z0-9-]+}, wird eine WARN geloggt — aufgeloest wird
 * trotzdem.</p>
 *
 * <h2>Beispiel (app.env)</h2>
 * <pre>
 * # Bootstrap des Vault-Clients (NIE in git/Code):
 * PLAINTEXT_VAULT_ENABLED=true
 * PLAINTEXT_VAULT_EMAIL=service@example.org
 * PLAINTEXT_VAULT_MASTER_PASSWORD=...
 * PLAINTEXT_VAULT_URL=https://vault.example.org
 *
 * # Secrets als vault:-Referenzen (Item-Name = app.key):
 * PLAINTEXT_BUCHHALTUNG_PAPERLESS_TOKEN=vault:guild.paperless-token
 * ZEIT_JIRA_USER=vault:app.jira-bit-admin#username
 * ZEIT_JIRA_PASSWORD=vault:app.jira-bit-admin
 * ZEIT_SCIFORMA_API_KEY=vault:app.sciforma#field:api-key
 * </pre>
 *
 * <h2>Fehlerverhalten</h2>
 * <p>Ist ein {@code vault:}-Wert nicht aufloesbar (Vault deaktiviert,
 * Login-Fehler oder Item/Feld fehlt), bricht der Boot per
 * {@link VaultwardenPropertyResolutionException} FAIL-FAST ab. Die Meldung nennt
 * nur Property- und Item-Namen — NIEMALS Secret-Werte oder das Master-Passwort.
 * Normale (nicht-{@code vault:}) Werte werden unveraendert durchgereicht.</p>
 *
 * <p><b>Der Abbruch gewinnt auch gegen einen Default.</b> Ein
 * {@code @Value("$&#123;plaintext.foo.token:&#125;")} faellt bei einer unaufloesbaren
 * {@code vault:}-Referenz NICHT auf den leeren Default zurueck — der Default greift nur, wenn das
 * Property ueberhaupt fehlt, nicht wenn seine Aufloesung scheitert. Das ist Absicht: ein leerer
 * Wert saehe aus wie eine harmlose Konfigurationsluecke, waehrend die App in Wahrheit ohne ihr
 * Secret liefe. Festgehalten in {@code VaultwardenFailFastVertragTest} (Karte 868).</p>
 */
@Slf4j
public class VaultwardenEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(VaultwardenPropertySource.SOURCE_NAME)) {
            return; // idempotent
        }
        sources.addFirst(new VaultwardenPropertySource(environment));
        log.debug("Vault-Property-Resolver registriert (Source '{}' an erster Stelle)",
                VaultwardenPropertySource.SOURCE_NAME);

        // Und JETZT alle vault:-Referenzen einmalig aufloesen und in ihrer Quell-Source ersetzen.
        // Die lazy Source allein genuegt nicht: Spring Boot haengt spaeter eine eigene Source davor
        // und reicht den Roh-Wert durch (siehe VaultwardenEagerResolution).
        VaultwardenEagerResolution.resolveAll(environment,
                new VaultwardenValueResolver(() -> VaultwardenPropertySource.buildService(environment)));
    }

    /**
     * Moeglichst spaet ausfuehren, damit unsere Source nach allen anderen EPPs
     * wirklich an erster Stelle steht.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

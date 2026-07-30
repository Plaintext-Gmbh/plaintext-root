/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * Auto-Configuration des Vaultwarden-Secret-Clients.
 *
 * <p>Wird ueber
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * automatisch von allen Consumer-Apps geerbt (wie {@code MenuAutoConfiguration}).
 * Die Beans werden IMMER registriert; ob tatsaechlich auf den Tresor zugegriffen
 * wird, entscheidet {@code plaintext.vault.enabled} — der Service ist fail-safe und
 * liefert bei {@code enabled=false} schlicht {@link java.util.Optional#empty()}.</p>
 */
@Configuration
@EnableConfigurationProperties(VaultwardenProperties.class)
@Slf4j
public class VaultwardenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    VaultwardenClient vaultwardenClient(VaultwardenProperties props,
                                        @Value("${spring.application.name:plaintext}") String appName) {
        log.info("Vaultwarden-Secret-Client registriert (enabled={}, url={})",
                props.isEnabled(), props.getUrl());
        return new VaultwardenClient(props, appName);
    }

    @Bean
    @ConditionalOnMissingBean
    public VaultwardenSecretService vaultwardenSecretService(VaultwardenProperties props,
                                                             VaultwardenClient client) {
        return new VaultwardenSecretService(props, client);
    }
}

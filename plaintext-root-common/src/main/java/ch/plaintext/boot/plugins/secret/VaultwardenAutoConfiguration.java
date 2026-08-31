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
 * Auto-configuration of the Vaultwarden secret client.
 *
 * <p>Is inherited automatically by all consumer apps through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * (like {@code MenuAutoConfiguration}). The beans are ALWAYS registered; whether the vault
 * is actually accessed is decided by {@code plaintext.vault.enabled} — the service is
 * fail-safe and simply returns {@link java.util.Optional#empty()} when
 * {@code enabled=false}.</p>
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

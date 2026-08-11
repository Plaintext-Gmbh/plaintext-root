/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken.config;

import ch.plaintext.apitoken.ApiTokenJtiRevocationChecker;
import ch.plaintext.apitoken.ApiTokenRevocationLookup;
import ch.plaintext.apitoken.JtiRevocationChecker;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Karte 664: stellt den Standard-{@link JtiRevocationChecker} bereit, der {@code revoke_api_token}
 * auch im JWT-Modus (app, guild, schuetu) wirksam macht.
 *
 * <p><b>Warum eine AutoConfiguration und kein {@code @Component}.</b> plaintext-schuetu bringt mit
 * {@code RevokedTokenService} bereits eine eigene Implementierung desselben Interface mit. Eine
 * zweite Bean hätte dort {@code ObjectProvider.getIfAvailable()} in
 * {@code McpBearerTokenFilterConfig} eine {@code NoUniqueBeanDefinitionException} werfen lassen —
 * ein Startfehler in schuetu, ausgelöst von einem Patch in root. {@code @ConditionalOnMissingBean}
 * löst genau das: Wo eine App eine eigene Blocklist betreibt, tritt diese hier zurück.</p>
 *
 * <p>Die Reihenfolge stimmt dabei verlässlich, und das ist der Grund für die AutoConfiguration:
 * Sie wird garantiert <em>nach</em> dem Component-Scan der Anwendung ausgewertet, sieht die
 * App-eigene Bean also bereits. Ein {@code @ConditionalOnMissingBean} in einer gewöhnlichen
 * {@code @Configuration} wäre von der Scan-Reihenfolge abhängig und damit ein Zufallsergebnis.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@AutoConfiguration
public class JtiRevocationAutoConfiguration {

    /**
     * @param lookup der leak-freie JDBC-Zugriff auf {@code api_token} (Karte 659). Ohne ihn gibt es
     *               keinen sinnvollen Standard-Checker — deshalb {@code @ConditionalOnBean}: Eine
     *               App ohne dieses Modul im Betrieb bekommt gar keine Bean und verhält sich wie
     *               vorher, statt beim Start an einer fehlenden Abhängigkeit zu scheitern.
     */
    @Bean
    @ConditionalOnBean(ApiTokenRevocationLookup.class)
    @ConditionalOnMissingBean(JtiRevocationChecker.class)
    JtiRevocationChecker apiTokenJtiRevocationChecker(ApiTokenRevocationLookup lookup) {
        return new ApiTokenJtiRevocationChecker(lookup);
    }
}

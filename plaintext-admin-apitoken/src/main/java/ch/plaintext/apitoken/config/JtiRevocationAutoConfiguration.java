/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
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
 * Card 664: provides the default {@link JtiRevocationChecker} that makes {@code revoke_api_token}
 * effective in JWT mode as well (app, guild, schuetu).
 *
 * <p><b>Why an AutoConfiguration and not a {@code @Component}.</b> With
 * {@code RevokedTokenService}, plaintext-schuetu already ships its own implementation of the same
 * interface. A second bean would have made {@code ObjectProvider.getIfAvailable()} in
 * {@code McpBearerTokenFilterConfig} throw a {@code NoUniqueBeanDefinitionException} there —
 * a startup failure in schuetu, triggered by a patch in root. {@code @ConditionalOnMissingBean}
 * solves exactly that: wherever an app runs its own blocklist, this one steps back.</p>
 *
 * <p>The ordering is reliable in this setup, and that is the reason for the AutoConfiguration:
 * it is guaranteed to be evaluated <em>after</em> the application's component scan, so it already
 * sees the app's own bean. A {@code @ConditionalOnMissingBean} in an ordinary
 * {@code @Configuration} would depend on the scan order and thus be a matter of chance.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@AutoConfiguration
public class JtiRevocationAutoConfiguration {

    /**
     * @param lookup the leak-free JDBC access to {@code api_token} (card 659). Without it there is
     *               no meaningful default checker — hence {@code @ConditionalOnBean}: an app that
     *               does not run this module gets no bean at all and behaves as before, instead of
     *               failing at startup on a missing dependency.
     */
    @Bean
    @ConditionalOnBean(ApiTokenRevocationLookup.class)
    @ConditionalOnMissingBean(JtiRevocationChecker.class)
    JtiRevocationChecker apiTokenJtiRevocationChecker(ApiTokenRevocationLookup lookup) {
        return new ApiTokenJtiRevocationChecker(lookup);
    }
}

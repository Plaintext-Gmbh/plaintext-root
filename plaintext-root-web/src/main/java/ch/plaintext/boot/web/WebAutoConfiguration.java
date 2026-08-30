/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.menu.MenuAutoConfiguration;
import ch.plaintext.boot.menu.SecurityProvider;
import ch.plaintext.boot.plugins.config.UrlRewriteConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Registers the reusable web infrastructure.
 *
 * <p><b>Why an AutoConfiguration.</b> The classes carry {@code @Component}/{@code @Controller} and
 * therefore only became beans if the consuming application component-scans {@code ch.plaintext}.
 * That holds for the root app; an application that picks individual modules silently did not get
 * them — it starts without any error message and the URL rewriting is simply missing. Exactly this
 * class of bug only surfaces in production.
 *
 * <p>All beans are {@link ConditionalOnMissingBean}; an application can therefore replace each one
 * of them with its own without anything here getting in the way.
 *
 * <p>The debug controllers are deliberately <b>not</b> registered here: they expose internal paths
 * and menu structures and therefore belong behind a deliberate decision by the consuming
 * application (component scan or its own {@code @Bean} declaration).
 *
 * @author plaintext.ch
 * @since 1.494.0
 */
@Configuration
@AutoConfigureBefore(MenuAutoConfiguration.class)
@ConditionalOnWebApplication
@Import(UrlRewriteConfig.class)
@Slf4j
public class WebAutoConfiguration {

    /**
     * Reads the roles of the logged-in user from the Spring Security context and answers the
     * menu's visibility questions with them.
     *
     * <p><b>The order is security-relevant here</b>, see {@link AutoConfigureBefore} on this
     * class. {@link MenuAutoConfiguration} offers a permissive default provider
     * ({@code hasRole} always returns {@code true}), so that an app without security gets a menu
     * at all. Both beans are {@link ConditionalOnMissingBean} — without the ordering the default
     * would win and every menu item would be visible to everyone. Because the page access guard
     * derives its rules from the very same visibility, all pages would then be reachable as well.
     * A fail-open without any error message.
     * {@code SecurityProviderReihenfolgeTest} pins this down.
     *
     * @return the role provider
     */
    @Bean
    @ConditionalOnMissingBean(SecurityProvider.class)
    public SpringSecurityProvider springSecurityProvider() {
        log.info("Registering SpringSecurityProvider");
        return new SpringSecurityProvider();
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.menu.SecurityProvider;
import ch.plaintext.boot.plugins.config.UrlRewriteConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the property this module exists for: the web infrastructure has to be available even
 * when the consuming application does <b>not</b> component-scan {@code ch.plaintext}.
 *
 * <p>The runner starts the AutoConfiguration and nothing else — no scan, no stereotypes. If
 * somebody were to tie the registration back to {@code @Component}, this test falls over. Without
 * it nothing falls over instead: the application starts cleanly and the URL rewriting is simply
 * missing, so that every {@code .html} URL runs into nothing.
 */
class WebAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebAutoConfiguration.class));

    @Test
    void stelltDieWebInfrastrukturOhneComponentScanBereit() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SpringSecurityProvider.class);
            assertThat(context).hasSingleBean(UrlRewriteConfig.class);
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
        });
    }

    /**
     * The role provider is the bridge between Spring Security and menu visibility. If an
     * application brings its own, that one has to win — otherwise it would have two sources for
     * the same question.
     */
    @Test
    void weichtEinemEigenenSecurityProvider() {
        runner.withBean(SecurityProvider.class, () -> new SecurityProvider() {
            @Override
            public boolean hasRole(String role) {
                return true;
            }

            @Override
            public boolean isSecurityEnabled() {
                return false;
            }
        }).run(context -> {
            assertThat(context).hasSingleBean(SecurityProvider.class);
            assertThat(context).doesNotHaveBean(SpringSecurityProvider.class);
        });
    }

    /** Without a servlet environment none of this may kick in. */
    @Test
    void bleibtAusserhalbEinerWebAnwendungStumm() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(SpringSecurityProvider.class));
    }
}

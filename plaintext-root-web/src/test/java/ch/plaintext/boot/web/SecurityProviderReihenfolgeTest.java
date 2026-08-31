/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.menu.MenuAutoConfiguration;
import ch.plaintext.boot.menu.SecurityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down which {@link SecurityProvider} wins when both AutoConfigurations run.
 *
 * <p><b>Why this deserves its own test.</b> {@code MenuAutoConfiguration} offers a
 * <i>permissive</i> default provider ({@code hasRole} always returns {@code true}), so that an app
 * without security gets a menu at all. {@link WebAutoConfiguration} offers the real one, bound to
 * Spring Security. Both are {@code @ConditionalOnMissingBean} — so whoever registers first wins.
 *
 * <p>If the default won, that would be a <b>fail-open</b>: every menu item would be visible to
 * everyone, and without any error message. Under {@code plaintext.menu.access-policy=strict} and
 * with the page access guard, the reachability of the pages depends on this as well. A bug like
 * that only surfaces in production once somebody sees entries that are none of their business.
 */
class SecurityProviderReihenfolgeTest {

    @Test
    void derEchteProviderGewinntGegenDenPermissivenDefault() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MenuAutoConfiguration.class, WebAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(SecurityProvider.class);
                    assertThat(context.getBean(SecurityProvider.class))
                            .as("Der an Spring Security gebundene Provider muss gewinnen; der "
                                    + "Default von MenuAutoConfiguration zeigt jedem alles")
                            .isInstanceOf(SpringSecurityProvider.class);
                    assertThat(context.getBean(SecurityProvider.class).isSecurityEnabled())
                            .as("isSecurityEnabled()==false ist die Signatur des Defaults")
                            .isTrue();
                });
    }
}

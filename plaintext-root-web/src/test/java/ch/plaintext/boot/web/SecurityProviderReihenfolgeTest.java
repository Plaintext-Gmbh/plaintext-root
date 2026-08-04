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
 * Haelt fest, welcher {@link SecurityProvider} gewinnt, wenn beide AutoConfigurations laufen.
 *
 * <p><b>Warum das ein eigener Test ist.</b> {@code MenuAutoConfiguration} bietet einen
 * <i>permissiven</i> Default-Provider an ({@code hasRole} liefert immer {@code true}), damit eine
 * App ohne Security ueberhaupt ein Menue bekommt. {@link WebAutoConfiguration} bietet den echten,
 * an Spring Security gebundenen an. Beide sind {@code @ConditionalOnMissingBean} — es gewinnt
 * also, wer zuerst registriert.
 *
 * <p>Gewaenne der Default, waere das ein <b>fail-open</b>: jeder Menuepunkt waere fuer jeden
 * sichtbar, und zwar ohne Fehlermeldung. Unter {@code plaintext.menu.access-policy=strict} und
 * mit dem Seiten-Zugriffsschutz haengt daran auch die Erreichbarkeit der Seiten. Ein solcher
 * Fehler faellt im Betrieb erst auf, wenn jemand Eintraege sieht, die ihn nichts angehen.
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

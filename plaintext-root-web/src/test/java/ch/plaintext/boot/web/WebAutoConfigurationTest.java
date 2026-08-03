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
 * Sichert die Eigenschaft, wegen der es dieses Modul gibt: die Web-Infrastruktur muss auch dann
 * bereitstehen, wenn die konsumierende Anwendung {@code ch.plaintext} <b>nicht</b>
 * component-scannt.
 *
 * <p>Der Runner startet ausschliesslich die AutoConfiguration — kein Scan, keine Stereotypen.
 * Wuerde jemand die Registrierung wieder an {@code @Component} haengen, faellt dieser Test um.
 * Ohne ihn faellt statt dessen nichts um: die Anwendung startet sauber, und das URL-Rewriting
 * fehlt einfach, sodass jede {@code .html}-URL ins Leere laeuft.
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
     * Der Rollen-Anbieter ist die Bruecke zwischen Spring Security und der Menue-Sichtbarkeit.
     * Bringt eine Anwendung einen eigenen mit, muss dieser gewinnen — sonst haette sie zwei
     * Quellen fuer dieselbe Frage.
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

    /** Ohne Servlet-Umgebung darf nichts davon anspringen. */
    @Test
    void bleibtAusserhalbEinerWebAnwendungStumm() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(SpringSecurityProvider.class));
    }
}

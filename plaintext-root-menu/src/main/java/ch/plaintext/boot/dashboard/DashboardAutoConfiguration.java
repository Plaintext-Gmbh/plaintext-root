/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the dashboard tile mechanism – analogous to
 * {@link ch.plaintext.boot.menu.MenuAutoConfiguration}.
 *
 * @author plaintext.ch
 */
@Configuration
@Slf4j
public class DashboardAutoConfiguration {

    @Bean
    public static TileRegistryPostProcessor tileRegistryPostProcessor() {
        log.info("Registriere TileRegistryPostProcessor");
        return new TileRegistryPostProcessor();
    }

    @Bean
    public DashboardTileModelBuilder dashboardTileModelBuilder() {
        log.info("Registriere DashboardTileModelBuilder");
        return new DashboardTileModelBuilder();
    }

    @Bean
    public TileVisibilityValidator tileVisibilityValidator(ApplicationContext applicationContext) {
        log.info("Registriere TileVisibilityValidator (Kachel-Menü-Kopplung)");
        return new TileVisibilityValidator(applicationContext);
    }
}

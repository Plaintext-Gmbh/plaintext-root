/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the menu module
 */
@Configuration
@EnableConfigurationProperties(ModuleRoleProperties.class)
@Slf4j
public class MenuAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SecurityProvider defaultSecurityProvider() {
        log.info("Using default SecurityProvider (no security)");
        return new SecurityProvider() {
            @Override
            public boolean hasRole(String role) {
                return true;
            }

            @Override
            public boolean isSecurityEnabled() {
                return false;
            }
        };
    }

    @Bean
    public static MenuRegistryPostProcessor menuRegistryPostProcessor() {
        log.info("Registering MenuRegistryPostProcessor");
        return new MenuRegistryPostProcessor();
    }

    @Bean
    public MenuModelBuilder menuModelBuilder() {
        log.info("Registering MenuModelBuilder");
        return new MenuModelBuilder();
    }

    /**
     * Registered here rather than annotated with {@code @Service}, so that an application which
     * does not component-scan {@code ch.plaintext} still gets the service.
     *
     * @return the menu role service
     */
    @Bean
    @ConditionalOnMissingBean
    public MenuRoleService menuRoleService() {
        log.info("Registering MenuRoleService");
        return new MenuRoleService();
    }

    /**
     * Setzt die konfigurierbaren Modul-Rollen durch ({@code plaintext.menu.module-roles}). Hier
     * registriert statt per {@code @Service} annotiert, damit auch Anwendungen ohne
     * {@code ch.plaintext}-Component-Scan den Service bekommen.
     *
     * @param applicationContext liefert die registrierten Menuepunkt-Beans
     * @param properties         die konfigurierte Modul-Rollen-Zuordnung
     * @return der Modul-Rollen-Service
     */
    @Bean
    @ConditionalOnMissingBean
    public ModuleRoleService moduleRoleService(ApplicationContext applicationContext,
                                               ModuleRoleProperties properties) {
        log.info("Registering ModuleRoleService (plaintext.menu.module-roles)");
        return new ModuleRoleService(applicationContext, properties);
    }

    /**
     * The registry carries {@code @Service} for the applications that component-scan
     * {@code ch.plaintext}; {@link ConditionalOnMissingBean} means they keep the scanned bean and
     * this method does nothing. Registering it here as well is what makes the registry — and
     * therefore everything built on it, notably the page access guard — available to an
     * application that consumes single modules rather than the whole framework. Without it such an
     * application starts cleanly and silently has no registry at all.
     *
     * @param applicationContext used to look up the registered menu item beans
     * @return the menu registry
     */
    @Bean
    @ConditionalOnMissingBean
    public MenuRegistryImpl menuRegistry(ApplicationContext applicationContext) {
        log.info("Registering MenuRegistry");
        return new MenuRegistryImpl(applicationContext);
    }
}

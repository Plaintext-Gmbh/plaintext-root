/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.MenuVisibilityProvider;
import ch.plaintext.boot.menu.SecurityProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Spring {@link BeanDefinitionRegistryPostProcessor}, der nach {@link DashboardTile} scannt und die
 * gefundenen Kacheln als Spring-Beans registriert – analog zum
 * {@link ch.plaintext.boot.menu.MenuRegistryPostProcessor}.
 *
 * @author plaintext.ch
 */
@Slf4j
public class TileRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {

    private SecurityProvider securityProvider;
    private MenuVisibilityProvider menuVisibilityProvider;

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        log.debug("TileRegistryPostProcessor: postProcessBeanDefinitionRegistry");
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Environment environment = beanFactory.getBean(Environment.class);
        // Eigene Property mit Fallback auf die Menü-Scan-Property bzw. ch.plaintext
        String menuDefault = environment.getProperty("plaintext.menu.scan-package", "ch.plaintext");
        String scanPackagesProperty = environment.getProperty("plaintext.dashboard.scan-package", menuDefault);

        String[] scanPackages = scanPackagesProperty.split(",");
        log.debug("Scanne nach Dashboard-Kacheln in Packages: {}", scanPackagesProperty);

        try {
            securityProvider = beanFactory.getBean(SecurityProvider.class);
        } catch (Exception e) {
            log.info("Kein SecurityProvider gefunden, Kacheln sind für alle Benutzer sichtbar");
        }

        try {
            menuVisibilityProvider = beanFactory.getBean(MenuVisibilityProvider.class);
            log.info("MenuVisibilityProvider gefunden, mandatsspezifische Kachel-Sichtbarkeit aktiviert");
        } catch (Exception e) {
            log.info("Kein MenuVisibilityProvider gefunden, Kachel-Sichtbarkeit nur über Rollen");
        }

        TileAnnotationScanner scanner = new TileAnnotationScanner(securityProvider, menuVisibilityProvider, beanFactory);

        int count = 0;
        for (String scanPackage : scanPackages) {
            String pkg = scanPackage.trim();
            List<TileItemImpl> tiles = scanner.findAnnotatedClasses(pkg);
            log.debug("{} Kacheln in Package {} gefunden", tiles.size(), pkg);

            for (TileItemImpl tile : tiles) {
                count++;
                String beanName = "dashboardTile" + count;
                beanFactory.registerSingleton(beanName, tile);
                log.debug("Kachel-Bean registriert: {} für '{}' (id: {}, order: {})",
                    beanName, tile.getTitle(), tile.getId(), tile.getOrder());
            }
        }

        log.info("Erfolgreich {} Dashboard-Kacheln aus {} Packages registriert", count, scanPackages.length);
    }
}

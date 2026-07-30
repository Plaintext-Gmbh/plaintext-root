/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.MenuVisibilityProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Spring BeanDefinitionRegistryPostProcessor that scans for @MenuAnnotation
 * and registers found menu items as Spring beans.
 */
@Slf4j
public class MenuRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {

    private SecurityProvider securityProvider;
    private MenuVisibilityProvider menuVisibilityProvider;

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        log.debug("MenuRegistryPostProcessor: postProcessBeanDefinitionRegistry");
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // Get scan package from Environment (not @Value - doesn't work in BeanFactoryPostProcessor)
        Environment environment = beanFactory.getBean(Environment.class);
        String scanPackagesProperty = environment.getProperty("plaintext.menu.scan-package", "ch.plaintext");

        log.debug("Raw property value: '{}'", scanPackagesProperty);
        log.debug("Property from environment: plaintext.menu.scan-package = {}",
            environment.getProperty("plaintext.menu.scan-package"));

        // Support comma-separated packages
        String[] scanPackages = scanPackagesProperty.split(",");

        log.debug("Scanning for menu annotations in packages: {} (split into {} packages)",
            scanPackagesProperty, scanPackages.length);

        // Try to get SecurityProvider from context (optional)
        try {
            securityProvider = beanFactory.getBean(SecurityProvider.class);
        } catch (Exception e) {
            log.info("No SecurityProvider found, menu items will be visible to all users");
        }

        // Try to get MenuVisibilityProvider from context (optional)
        try {
            menuVisibilityProvider = beanFactory.getBean(MenuVisibilityProvider.class);
            log.info("MenuVisibilityProvider found, mandate-specific menu visibility will be enabled");
        } catch (Exception e) {
            log.info("No MenuVisibilityProvider found, menu visibility will be based on roles only");
        }

        // Pass the BeanFactory to scanner so menu items can lazy-load MenuVisibilityProvider
        MenuAnnotationScanner scanner = new MenuAnnotationScanner(securityProvider, menuVisibilityProvider, beanFactory);

        int count = 0;
        for (String scanPackage : scanPackages) {
            String pkg = scanPackage.trim();
            log.debug("Scanning package: {}", pkg);
            List<MenuItemImpl> menuItems = scanner.findAnnotatedClasses(pkg);
            log.debug("Found {} menu items in package {}", menuItems.size(), pkg);

            for (MenuItemImpl menuItem : menuItems) {
                count++;
                String beanName = "menuItem" + count;
                beanFactory.registerSingleton(beanName, menuItem);
                log.debug("Registered menu item bean: {} for '{}' (order: {}, parent: '{}')",
                    beanName, menuItem.getTitle(), menuItem.getOrder(), menuItem.getParent());
            }
        }

        log.info("Successfully registered {} menu items from {} packages", count, scanPackages.length);
    }
}

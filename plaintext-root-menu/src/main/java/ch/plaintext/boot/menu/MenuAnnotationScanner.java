/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.MenuVisibilityProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Scanner for finding classes annotated with @MenuAnnotation
 */
@Slf4j
@RequiredArgsConstructor
public class MenuAnnotationScanner {

    private final SecurityProvider securityProvider;
    private final MenuVisibilityProvider menuVisibilityProvider;
    private final BeanFactory beanFactory;
    private final MenuAccessPolicy accessPolicy;

    /**
     * Keeps the three-argument form working for callers that predate the access policy; they get
     * {@link MenuAccessPolicy#PERMISSIVE}, the historic behaviour.
     *
     * @param securityProvider       role lookup, may be null
     * @param menuVisibilityProvider mandate lookup, may be null
     * @param beanFactory            used for lazy provider lookup, may be null
     */
    public MenuAnnotationScanner(SecurityProvider securityProvider,
                                 MenuVisibilityProvider menuVisibilityProvider,
                                 BeanFactory beanFactory) {
        this(securityProvider, menuVisibilityProvider, beanFactory, MenuAccessPolicy.PERMISSIVE);
    }

    public List<MenuItemImpl> findAnnotatedClasses(String scanPackage) {
        List<MenuItemImpl> menuItems = new ArrayList<>();

        // Validate input - return empty list for null or empty package
        if (scanPackage == null || scanPackage.trim().isEmpty()) {
            log.warn("Scan package is null or empty, returning empty menu items list");
            return menuItems;
        }

        ClassPathScanningCandidateComponentProvider provider = createComponentScanner();

        for (BeanDefinition beanDef : provider.findCandidateComponents(scanPackage)) {
            MenuItemImpl menuItem = createMenuItem(beanDef);
            if (menuItem != null) {
                menuItems.add(menuItem);
            }
        }

        return menuItems;
    }

    private ClassPathScanningCandidateComponentProvider createComponentScanner() {
        ClassPathScanningCandidateComponentProvider provider =
            new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(MenuAnnotation.class));
        return provider;
    }

    private MenuItemImpl createMenuItem(BeanDefinition beanDef) {
        try {
            Class<?> clazz = Class.forName(beanDef.getBeanClassName());
            MenuAnnotation annotation = clazz.getAnnotation(MenuAnnotation.class);

            MenuItemImpl menuItem = new MenuItemImpl();
            menuItem.setCommand(annotation.link());
            menuItem.setOrder(annotation.order());
            menuItem.setRoles(Arrays.asList(annotation.roles()));
            menuItem.setParent(annotation.parent());
            menuItem.setTitle(annotation.title());
            menuItem.setIcon(annotation.icon());
            menuItem.setModuleId(annotation.moduleId());
            menuItem.setMenuId(annotation.menuId());
            menuItem.setRoleStartsWith(Arrays.asList(annotation.roleStartsWith()));
            menuItem.setAccessPolicy(accessPolicy);
            menuItem.setSecurityProvider(securityProvider);
            menuItem.setMenuVisibilityProvider(menuVisibilityProvider);
            menuItem.setBeanFactory(beanFactory);

            log.debug("Found Menu Item: {} (order: {}, parent: {})",
                menuItem.getTitle(), menuItem.getOrder(), menuItem.getParent());

            return menuItem;
        } catch (Exception e) {
            log.error("Failed to create menu item from bean definition: {}", beanDef.getBeanClassName(), e);
        }
        return null;
    }
}

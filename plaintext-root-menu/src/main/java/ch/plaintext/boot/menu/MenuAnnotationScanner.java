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
import org.springframework.core.env.Environment;
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
     * The application environment — optional. When it is set, the scanner evaluates Spring
     * conditions ({@code @Conditional}, {@code @ConditionalOnProperty}, {@code @Profile}) on the
     * {@code @MenuAnnotation} classes against the real configuration; without it the condition
     * only sees system properties and environment variables (the Spring default).
     *
     * <p>Task from Daniel, 29.08.2026: the menu item "Swagger" appeared in the root menu of every
     * app even though springdoc is switched off in PROD — the click ran into a 404 and from there
     * on to the dashboard. With {@code @ConditionalOnProperty} on the menu class it disappears
     * when there is nothing to show.</p>
     */
    private Environment environment;

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

    /**
     * Sets the environment for evaluating {@code @Conditional} annotations on menu classes.
     *
     * @param umgebung the Spring environment, may be {@code null}
     * @return this scanner (fluent)
     */
    public MenuAnnotationScanner mitUmgebung(Environment umgebung) {
        this.environment = umgebung;
        return this;
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
        if (environment != null) {
            // The provider checks @Conditional itself (isConditionMatch) — but only against the
            // environment it knows. Without this line that would be a StandardEnvironment without
            // application.yml, and @ConditionalOnProperty would never see the configuration.
            provider.setEnvironment(environment);
        }
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

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.MenuRegistry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of MenuRegistry that provides access to all registered menu items.
 *
 * @author plaintext.ch
 * @since 1.42.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuRegistryImpl implements MenuRegistry {

    private final ApplicationContext applicationContext;

    @Override
    public List<String> getAllMenuTitles() {
        Map<String, MenuItemImpl> menuBeans = applicationContext.getBeansOfType(MenuItemImpl.class);
        log.debug("Found {} MenuItemImpl beans in ApplicationContext", menuBeans.size());

        List<String> titles = menuBeans.values().stream()
            .map(this::buildFullTitle)
            .sorted()
            .distinct()
            .collect(Collectors.toList());

        log.info("Returning {} menu titles: {}", titles.size(), titles);
        return titles;
    }

    @Override
    public List<MenuItem> getAllMenuItems() {
        Map<String, MenuItemImpl> menuBeans = applicationContext.getBeansOfType(MenuItemImpl.class);

        // Return MenuItemImpl directly - it implements MenuItem interface
        // Cast each item to MenuItem
        return menuBeans.values().stream()
            .map(item -> (MenuItem) item)
            .collect(Collectors.toList());
    }

    /**
     * Get all menu items as MenuItemImpl (without interface casting).
     * Used by PageAccessGuardService to avoid classloader issues in Spring Boot JAR.
     */
    public List<MenuItemImpl> getAllMenuItemsImpl() {
        Map<String, MenuItemImpl> menuBeans = applicationContext.getBeansOfType(MenuItemImpl.class);
        return new ArrayList<>(menuBeans.values());
    }

    private String buildFullTitle(MenuItemImpl item) {
        if (item.getParent() == null || item.getParent().trim().isEmpty()) {
            return item.getTitle();
        }
        return item.getParent() + " | " + item.getTitle();
    }
}

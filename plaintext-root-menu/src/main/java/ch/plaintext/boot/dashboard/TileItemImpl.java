/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.MenuVisibilityProvider;
import ch.plaintext.TileRegistry;
import ch.plaintext.boot.menu.ModuleRoleService;
import ch.plaintext.boot.menu.SecurityProvider;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of a dashboard tile – analogous to
 * {@link ch.plaintext.boot.menu.MenuItemImpl}. Holds the metadata of the {@code @DashboardTile}
 * annotation and decides on visibility (roles + {@link MenuVisibilityProvider}).
 *
 * @author plaintext.ch
 */
@Data
@Slf4j
public class TileItemImpl implements TileRegistry.TileItem {

    private String id = "";
    private String title = "";
    private String icon = "";
    private String image = "";
    private String link = "";
    private int order = 100;
    private String menuTitle = "";
    private List<String> roles = new ArrayList<>();
    private SecurityProvider securityProvider;
    private MenuVisibilityProvider menuVisibilityProvider;
    private ModuleRoleService moduleRoleService;
    private BeanFactory beanFactory;

    /**
     * Returns the title against which the tenant-specific visibility is checked:
     * {@link #menuTitle} if set, otherwise {@link #title}.
     */
    public String getVisibilityTitle() {
        return (menuTitle == null || menuTitle.trim().isEmpty()) ? title : menuTitle;
    }

    /**
     * Configurable module role ({@code plaintext.menu.module-roles}): the tile is assigned to the
     * module menu through its {@link #link} — alternatively through its {@link #menuTitle} — and
     * therefore disappears consistently with the menu item. {@code admin}/{@code root} bypass the
     * check.
     *
     * @return {@code true} if no module role is required or the user holds it
     */
    private boolean isModuleRoleVisible() {
        if (moduleRoleService == null && beanFactory != null) {
            try {
                moduleRoleService = beanFactory.getBean(ModuleRoleService.class);
            } catch (Exception e) {
                log.debug("Kein ModuleRoleService verfügbar für Kachel '{}': {}", title, e.getMessage());
            }
        }
        if (moduleRoleService == null) {
            return true;
        }
        boolean visible = moduleRoleService.isAllowedForLink(link, getVisibilityTitle(), securityProvider);
        if (!visible) {
            log.debug("Modul-Rolle fehlt - Kachel '{}' ausgeblendet", title);
        }
        return visible;
    }

    @Override
    public boolean isOn() {
        // First check the role-based visibility
        if (roles != null && !roles.isEmpty() && securityProvider != null) {
            boolean hasRole = false;
            for (String role : roles) {
                String upperRole = role != null ? role.toUpperCase() : role;
                if (securityProvider.hasRole(upperRole)) {
                    hasRole = true;
                    break;
                }
            }
            if (!hasRole) {
                return false;
            }
        }

        // Configurable module role: a tile disappears together with its module menu.
        if (!isModuleRoleVisible()) {
            return false;
        }

        // Load the MenuVisibilityProvider lazily from the BeanFactory if needed
        if (menuVisibilityProvider == null && beanFactory != null) {
            try {
                menuVisibilityProvider = beanFactory.getBean(MenuVisibilityProvider.class);
                log.debug("Lazy-loaded MenuVisibilityProvider für Kachel: {}", title);
            } catch (Exception e) {
                log.debug("Kein MenuVisibilityProvider verfügbar für Kachel '{}': {}", title, e.getMessage());
            }
        }

        // Check the tenant-specific visibility if a provider is available
        if (menuVisibilityProvider != null) {
            String fullTitle = getVisibilityTitle();
            boolean visible = menuVisibilityProvider.isMenuVisible(fullTitle);
            if (!visible) {
                log.debug("MenuVisibilityProvider hat Kachel ausgeblendet: {}", fullTitle);
            }
            return visible;
        }

        return true;
    }
}

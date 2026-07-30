/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.MenuVisibilityProvider;
import ch.plaintext.modules.ModuleEnablementProvider;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of a menu item
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class MenuItemImpl extends AbstractMenuItem {

    private int order = 0;
    private String title = "";
    private String parent = "";
    private String command = "";
    private String icon = "";
    private List<String> roles = new ArrayList<>();
    private String moduleId = "";
    private SecurityProvider securityProvider;
    private MenuVisibilityProvider menuVisibilityProvider;
    private ModuleEnablementProvider moduleEnablementProvider;
    private BeanFactory beanFactory;
    private String badge;

    @Override
    public String getIc() {
        return icon;
    }

    public String getLink() {
        return command;
    }

    @Override
    public boolean isOn() {
        // Check role-based visibility first
        if (roles != null && !roles.isEmpty() && securityProvider != null) {
            boolean hasRole = false;
            for (String role : roles) {
                // Convert role to uppercase for case-insensitive comparison
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

        // Check module enablement (Task #016 Phase 2) — disabled module hides all its menu items.
        if (moduleId != null && !moduleId.isBlank()) {
            if (moduleEnablementProvider == null && beanFactory != null) {
                try {
                    moduleEnablementProvider = beanFactory.getBean(ModuleEnablementProvider.class);
                } catch (Exception e) {
                    log.debug("No ModuleEnablementProvider available for menu '{}': {}", title, e.getMessage());
                }
            }
            if (moduleEnablementProvider != null && !moduleEnablementProvider.isEnabled(moduleId)) {
                log.debug("ModuleEnablementProvider hid menu '{}' (module '{}' disabled)", title, moduleId);
                return false;
            }
        }

        // Lazy load MenuVisibilityProvider if not set and BeanFactory is available
        if (menuVisibilityProvider == null && beanFactory != null) {
            try {
                menuVisibilityProvider = beanFactory.getBean(MenuVisibilityProvider.class);
                log.debug("Lazy-loaded MenuVisibilityProvider for menu: {}", title);
            } catch (Exception e) {
                // MenuVisibilityProvider not available, that's ok
                log.debug("No MenuVisibilityProvider available for menu '{}': {}", title, e.getMessage());
            }
        } else if (menuVisibilityProvider == null) {
            log.debug("Cannot lazy-load MenuVisibilityProvider for menu '{}' - beanFactory is null", title);
        }

        // Check mandate-specific visibility if provider is available
        if (menuVisibilityProvider != null) {
            String fullTitle = buildFullTitle();
            boolean visible = menuVisibilityProvider.isMenuVisible(fullTitle);
            if (!visible) {
                log.debug("MenuVisibilityProvider hid menu: {}", fullTitle);
            }
            return visible;
        }

        return true;
    }

    /**
     * Builds the full menu title including parent hierarchy.
     * E.g., "Root | Mandate" or "Zeiterfassung | Zeiterfassung Einstellungen"
     */
    public String buildFullTitle() {
        if (parent == null || parent.trim().isEmpty()) {
            return title;
        }
        return parent + " | " + title;
    }
}

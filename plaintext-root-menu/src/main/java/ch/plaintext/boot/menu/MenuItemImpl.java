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
 * Implementation of a menu item.
 * <p>
 * Visibility is decided in four independent steps, all of which must pass: the role check, whose
 * rules depend on the configured {@link MenuAccessPolicy}; the configurable module-role check via
 * {@link ModuleRoleService}; the module enablement check via {@link ModuleEnablementProvider}; and
 * the mandate check via {@link MenuVisibilityProvider}.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class MenuItemImpl extends AbstractMenuItem {

    public static final String ROOT_MENU_TITLE = "Root";
    public static final String ROLE_ROOT = "ROLE_ROOT";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_MENU_PREFIX = "ROLE_MENU_";

    private int order = 0;
    private String title = "";
    private String parent = "";
    private String command = "";
    private String icon = "";
    private List<String> roles = new ArrayList<>();
    private List<String> roleStartsWith = new ArrayList<>();
    private String menuId = "";
    private MenuAccessPolicy accessPolicy = MenuAccessPolicy.PERMISSIVE;
    private String moduleId = "";
    private SecurityProvider securityProvider;
    private MenuVisibilityProvider menuVisibilityProvider;
    private ModuleEnablementProvider moduleEnablementProvider;
    private ModuleRoleService moduleRoleService;
    private BeanFactory beanFactory;
    private String badge;

    /**
     * Rollen (GROSS, ohne {@code ROLE_}-Prefix), die die Anwendungs-Konfiguration
     * ({@code plaintext.menu.module-roles}) fuer das Modul dieses Menuepunkts fordert. Wird vom
     * {@link ModuleRoleService} einmalig gesetzt; leer heisst „unkonfiguriert" und damit
     * unveraendertes Verhalten.
     */
    private List<String> moduleRoles = new ArrayList<>();

    @Override
    public String getIc() {
        return icon;
    }

    public String getLink() {
        return command;
    }

    /**
     * Returns the auto-derived role for this menu item, e.g. {@code ROLE_MENU_CRON}. Only
     * meaningful under {@link MenuAccessPolicy#STRICT}.
     *
     * @return the derived role name
     */
    public String getAutoRole() {
        return ROLE_MENU_PREFIX + getEffectiveMenuId().toUpperCase();
    }

    /**
     * Returns a stable identifier for this menu item. Prefers the explicit {@code menuId},
     * otherwise derives one from parent+title: German umlauts are transliterated (ä→ae, ö→oe,
     * ü→ue, ß→ss), then lower-cased, and every run of non-alphanumeric characters becomes a
     * single underscore.
     *
     * @return the effective menu identifier
     */
    public String getEffectiveMenuId() {
        if (menuId != null && !menuId.isBlank()) {
            return menuId;
        }
        String base = (parent == null || parent.isBlank()) ? title : parent + "_" + title;
        String transliterated = base
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
                .replace("ß", "ss");
        return stripEdgeUnderscores(transliterated.toLowerCase().replaceAll("[^a-z0-9]+", "_"));
    }

    /**
     * Removes leading and trailing underscores in linear time.
     *
     * <p>Replaces the former {@code replaceAll("^_+|_+$", "")}. The {@code _+$} branch is quadratic:
     * for a run of underscores the engine matches the whole run at every start position and only
     * then fails on {@code $} (Sonar {@code java:S5852}). Measured on {@code "x" + "_"*n + "x"}:
     * 2233 ms at n=20000, 7726 ms at n=40000, 36522 ms at n=80000. Two index scans do the same job
     * in one pass over the string.</p>
     *
     * @param s the already normalized identifier
     * @return the identifier without leading/trailing underscores
     */
    private static String stripEdgeUnderscores(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && s.charAt(from) == '_') {
            from++;
        }
        while (to > from && s.charAt(to - 1) == '_') {
            to--;
        }
        return s.substring(from, to);
    }

    /**
     * True if this menu lives under the Root menu, or is the Root menu itself. Root children are
     * reserved for ROLE_ROOT and stay closed to ROLE_ADMIN.
     *
     * @return true if the item belongs to the Root menu
     */
    public boolean isUnderRootMenu() {
        return ROOT_MENU_TITLE.equals(title) || ROOT_MENU_TITLE.equals(parent);
    }

    @Override
    public boolean isOn() {
        if (!isRoleVisible()) {
            return false;
        }
        if (!isModuleRoleVisible()) {
            return false;
        }
        if (!isModuleVisible()) {
            return false;
        }
        return isMandateVisible();
    }

    /**
     * Konfigurierbare Modul-Rolle ({@code plaintext.menu.module-roles.<key>=<rolle>}): schaltet ein
     * ganzes Modul pro Anwendung ein oder aus. Gilt unter beiden Access-Policies und — weil der
     * {@code PageAccessGuard} ueber {@link #isOn()} geht — auch fuer den Direktaufruf der Seiten.
     * {@code admin}/{@code root} umgehen die Pruefung (im {@link ModuleRoleService}).
     */
    private boolean isModuleRoleVisible() {
        if (moduleRoleService == null && beanFactory != null) {
            try {
                moduleRoleService = beanFactory.getBean(ModuleRoleService.class);
            } catch (Exception e) {
                log.debug("No ModuleRoleService available for menu '{}': {}", title, e.getMessage());
            }
        }
        if (moduleRoleService != null) {
            // Fuellt moduleRoles an allen registrierten Menuepunkten (einmalig).
            moduleRoleService.ensureResolved();
        }
        boolean visible = ModuleRoleService.holdsAny(moduleRoles, securityProvider);
        if (!visible) {
            log.debug("Modul-Rolle {} fehlt - Menuepunkt '{}' ausgeblendet", moduleRoles, title);
        }
        return visible;
    }

    /**
     * Role half of the visibility decision, dispatched by access policy.
     */
    private boolean isRoleVisible() {
        if (accessPolicy == MenuAccessPolicy.STRICT) {
            return isStrictRoleVisible();
        }

        // PERMISSIVE: unchanged historic behaviour — only a non-matching explicit roles list
        // hides the item.
        if (roles != null && !roles.isEmpty() && securityProvider != null) {
            for (String role : roles) {
                // Convert role to uppercase for case-insensitive comparison
                String upperRole = role != null ? role.toUpperCase() : role;
                if (securityProvider.hasRole(upperRole)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * STRICT: hidden unless a rule explicitly admits the user.
     */
    private boolean isStrictRoleVisible() {
        if (securityProvider == null) {
            return true;
        }
        if (securityProvider.hasRole(ROLE_ROOT)) {
            return true;
        }
        if (securityProvider.hasRole(ROLE_ADMIN) && !isUnderRootMenu()) {
            return true;
        }
        if (securityProvider.hasRole(getAutoRole())) {
            return true;
        }
        if (roles != null) {
            for (String role : roles) {
                if (role != null && !role.isBlank() && securityProvider.hasRole(role)) {
                    return true;
                }
            }
        }
        if (roleStartsWith != null) {
            for (String prefix : roleStartsWith) {
                if (prefix != null && !prefix.isBlank() && securityProvider.hasAnyRoleStartingWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Module half of the visibility decision (Task #016 Phase 2) — a disabled module hides all
     * its menu items. Applies under both policies.
     */
    private boolean isModuleVisible() {
        if (moduleId == null || moduleId.isBlank()) {
            return true;
        }
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
        return true;
    }

    /**
     * Mandate half of the visibility decision — applies under both policies.
     */
    private boolean isMandateVisible() {
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

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Central service that knows every registered menu item and its derived role.
 * <p>
 * Used by roles-and-rights screens to list the assignable menu roles, and to map a request path
 * back to the menu item that owns it. Unlike {@link MenuRegistryImpl}, this service ignores the
 * current user's permissions and always reports the full set — a screen that hands out roles must
 * show the roles the current admin does not hold.
 * <p>
 * Registered by {@link MenuAutoConfiguration} rather than by component scanning, so that consuming
 * applications need not scan {@code ch.plaintext}.
 *
 * @since 1.480.0
 */
@Slf4j
public class MenuRoleService {

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * @return all known menu items, sorted by parent, order and title, regardless of the current
     *         user's permissions
     */
    public List<MenuItemImpl> getAllMenuItems() {
        Map<String, MenuItemImpl> beans = applicationContext.getBeansOfType(MenuItemImpl.class);
        List<MenuItemImpl> all = new ArrayList<>(beans.values());
        all.sort(Comparator.comparing((MenuItemImpl m) -> m.getParent() == null ? "" : m.getParent())
                .thenComparingInt(MenuItemImpl::getOrder)
                .thenComparing(MenuItemImpl::getTitle));
        return all;
    }

    /**
     * @return map of derived role name to menu item, sorted alphabetically by role
     */
    public Map<String, MenuItemImpl> getMenuRoleMap() {
        Map<String, MenuItemImpl> result = new TreeMap<>();
        for (MenuItemImpl item : getAllMenuItems()) {
            String autoRole = item.getAutoRole();
            if (autoRole != null && !autoRole.isBlank()) {
                result.put(autoRole, item);
            }
        }
        return result;
    }

    /**
     * Resolves a menu item by its command/link path.
     *
     * @param path request path, e.g. {@code /repos.html}
     * @return the matching menu item, or null when the path belongs to no menu
     */
    public MenuItemImpl findByPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = normalizePath(path);
        for (MenuItemImpl item : getAllMenuItems()) {
            String cmd = item.getCommand();
            if (cmd == null || cmd.isBlank() || cmd.equals("#")) {
                continue;
            }
            if (normalizePath(cmd).equals(normalized)) {
                return item;
            }
        }
        return null;
    }

    private String normalizePath(String path) {
        String p = path.trim();
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        if (p.endsWith(".xhtml")) {
            p = p.substring(0, p.length() - ".xhtml".length()) + ".html";
        }
        return p;
    }
}

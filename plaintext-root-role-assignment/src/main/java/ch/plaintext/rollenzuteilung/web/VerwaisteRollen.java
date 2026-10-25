/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.web;

import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.boot.menu.ModuleRoleProperties;
import ch.plaintext.boot.menu.ModuleRoleService;
import ch.plaintext.framework.ModuleRoleDeclarationProvider;
import ch.plaintext.framework.PlaintextRoleProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ch.plaintext.boot.menu.ModuleRoleProperties.canonicalRole;

/**
 * Card 1521: which assigned roles belong to a module that this instance has not loaded (switched off
 * via the ModulLader). Only two origins are known exactly, everything else counts as "not orphaned":
 * <ul>
 *   <li>{@code ROLE_MENU_<id>} belongs to the menu {@code <id>}: orphaned if no loaded menu item derives
 *       it ({@link MenuItemImpl#getAutoRole()}).</li>
 *   <li>A role from {@code plaintext.menu.module-roles}: orphaned if none of its module keys is loaded
 *       ({@link ModuleRoleService#getKnownModuleKeys()}) and nothing loaded uses it otherwise - no role
 *       provider besides the configuration, no {@code roles}/{@code roleStartsWith} of a loaded menu.</li>
 * </ul>
 */
@Component
public class VerwaisteRollen {

    private static final String MENU = "MENU_";

    private final ApplicationContext ctx;

    public VerwaisteRollen(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    /** @return role name -> module key or menu id that is not loaded; roles not orphaned are absent */
    public Map<String, String> gruende(Collection<String> rollen) {
        Collection<MenuItemImpl> menues = ctx.getBeansOfType(MenuItemImpl.class).values();
        ModuleRoleService moduleRoles = ctx.getBeanProvider(ModuleRoleService.class).getIfAvailable();
        ModuleRoleProperties props = ctx.getBeanProvider(ModuleRoleProperties.class).getIfAvailable();
        Set<String> andere = new HashSet<>();
        for (PlaintextRoleProvider p : ctx.getBeansOfType(PlaintextRoleProvider.class).values()) {
            if (!(p instanceof ModuleRoleDeclarationProvider) && p.getDeclaredRoles() != null) {
                p.getDeclaredRoles().forEach(r -> andere.add(canonicalRole(r.name())));
            }
        }
        return gruende(rollen, menues, moduleRoles == null ? null : moduleRoles.getKnownModuleKeys(),
                props == null ? Map.of() : props.canonicalModuleRoles(), andere);
    }

    static Map<String, String> gruende(Collection<String> rollen, Collection<MenuItemImpl> menues,
                                       Set<String> bekannteKeys, Map<String, String> konfig, Set<String> andere) {
        Map<String, String> ret = new HashMap<>();
        for (String rolle : rollen) {
            String grund = grund(rolle, menues, bekannteKeys, konfig, andere);
            if (grund != null) {
                ret.put(rolle, grund);
            }
        }
        return ret;
    }

    static String grund(String rolle, Collection<MenuItemImpl> menues, Set<String> bekannteKeys,
                        Map<String, String> konfig, Set<String> andere) {
        String c = canonicalRole(rolle);
        if (c.isEmpty()) {
            return null;
        }
        if (c.startsWith(MENU)) {
            boolean geladen = menues.stream().anyMatch(m -> canonicalRole(m.getAutoRole()).equals(c));
            return geladen ? null : c.substring(MENU.length()).toLowerCase(Locale.ROOT);
        }
        // Without the service the loaded module keys are unknown - then no statement at all.
        if (bekannteKeys == null) {
            return null;
        }
        List<String> keys = konfig.entrySet().stream().filter(e -> e.getValue().equals(c)).map(Map.Entry::getKey).toList();
        if (keys.isEmpty() || keys.stream().anyMatch(bekannteKeys::contains) || andere.contains(c)
                || menues.stream().anyMatch(m -> nutzt(m, c))) {
            return null;
        }
        return String.join(", ", keys);
    }

    private static boolean nutzt(MenuItemImpl m, String c) {
        if (m.getRoles() != null && m.getRoles().stream().anyMatch(r -> canonicalRole(r).equals(c))) {
            return true;
        }
        return m.getRoleStartsWith() != null && m.getRoleStartsWith().stream()
                .filter(p -> p != null && !p.isBlank())
                .anyMatch(p -> c.startsWith(canonicalRole(p)));
    }
}

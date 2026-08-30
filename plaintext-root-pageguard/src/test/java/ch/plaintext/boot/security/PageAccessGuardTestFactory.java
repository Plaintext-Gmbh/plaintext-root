/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.boot.menu.MenuRegistryImpl;
import ch.plaintext.boot.menu.SecurityProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds real {@link PageAccessGuardService} instances with real {@link MenuItemImpl} objects for
 * the tests.
 *
 * <p>Deliberately no mocks for {@code MenuItemImpl}: the role/parent logic of
 * {@link MenuItemImpl#isOn()} is part of what is meant to be checked here. Only the registry is
 * mocked.
 */
final class PageAccessGuardTestFactory {

    private PageAccessGuardTestFactory() {
    }

    /** A visible menu item without a role restriction. */
    static MenuItemImpl menu(String link, boolean sichtbar) {
        MenuItemImpl item = new MenuItemImpl();
        item.setTitle(link);
        item.setCommand(link);
        if (!sichtbar) {
            item.setRoles(List.of("ROOT"));
            item.setSecurityProvider(rolle -> false);
        }
        return item;
    }

    /**
     * Menu item with title, parent menu and roles; the visibility follows from the roles of the
     * simulated user.
     */
    static MenuItemImpl menu(String titel, String parent, String link, Set<String> benutzerRollen, String... rollen) {
        MenuItemImpl item = new MenuItemImpl();
        item.setTitle(titel);
        item.setParent(parent == null ? "" : parent);
        item.setCommand(link == null ? "" : link);
        item.setRoles(Arrays.asList(rollen));
        item.setSecurityProvider(securityProvider(benutzerRollen));
        return item;
    }

    /** {@link SecurityProvider} that knows exactly the given roles (case-insensitive). */
    static SecurityProvider securityProvider(Set<String> benutzerRollen) {
        Set<String> gross = benutzerRollen.stream()
                .map(r -> r.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return rolle -> rolle != null && gross.contains(rolle.toUpperCase(Locale.ROOT));
    }

    static PageAccessGuardService strictMitMenues(MenuItemImpl... items) {
        return guard(PageGuardMode.STRICT, true, items);
    }

    static PageAccessGuardService reportMitMenues(MenuItemImpl... items) {
        return guard(PageGuardMode.REPORT, true, items);
    }

    static PageAccessGuardService guard(PageGuardMode mode, boolean enabled, MenuItemImpl... items) {
        return guard(eigenschaften(mode, enabled), items);
    }

    static PageAccessGuardService guard(PageGuardProperties properties, MenuItemImpl... items) {
        MenuRegistryImpl registry = mock(MenuRegistryImpl.class);
        when(registry.getAllMenuItemsImpl()).thenReturn(new java.util.ArrayList<>(Arrays.asList(items)));
        return new PageAccessGuardService(registry, properties);
    }

    /** Registry that throws an exception on access (fail-closed proof). */
    static PageAccessGuardService guardMitFehler(PageGuardMode mode) {
        MenuRegistryImpl registry = mock(MenuRegistryImpl.class);
        when(registry.getAllMenuItemsImpl()).thenThrow(new IllegalStateException("Menu registry kaputt"));
        return new PageAccessGuardService(registry, eigenschaften(mode, true));
    }

    static PageGuardProperties eigenschaften(PageGuardMode mode, boolean enabled) {
        PageGuardProperties properties = new PageGuardProperties();
        properties.setMode(mode);
        properties.setEnabled(enabled);
        return properties;
    }
}

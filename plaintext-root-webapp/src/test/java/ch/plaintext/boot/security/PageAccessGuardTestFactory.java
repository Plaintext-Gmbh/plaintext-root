/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.boot.menu.MenuRegistryImpl;
import ch.plaintext.boot.menu.SecurityProvider;
import ch.plaintext.boot.plugins.security.PlaintextSecurityProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Baut echte {@link PageAccessGuardService}-Instanzen mit echten {@link MenuItemImpl}-Objekten fuer
 * die Tests.
 *
 * <p>Absichtlich keine Mocks fuer {@code MenuItemImpl}: die Rollen-/Eltern-Logik von
 * {@link MenuItemImpl#isOn()} ist Teil dessen, was hier geprueft werden soll. Gemockt wird nur die
 * Registry.
 */
final class PageAccessGuardTestFactory {

    private PageAccessGuardTestFactory() {
    }

    /** Ein sichtbarer Menuepunkt ohne Rollenbeschraenkung. */
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
     * Menuepunkt mit Titel, Elternmenue und Rollen; die Sichtbarkeit ergibt sich aus den Rollen des
     * simulierten Benutzers.
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

    /** {@link SecurityProvider}, der genau die angegebenen Rollen kennt (case-insensitive). */
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

    static PageAccessGuardService guard(PlaintextSecurityProperties properties, MenuItemImpl... items) {
        MenuRegistryImpl registry = mock(MenuRegistryImpl.class);
        when(registry.getAllMenuItemsImpl()).thenReturn(new java.util.ArrayList<>(Arrays.asList(items)));
        return new PageAccessGuardService(registry, properties);
    }

    /** Registry, die beim Zugriff eine Exception wirft (fail-closed-Nachweis). */
    static PageAccessGuardService guardMitFehler(PageGuardMode mode) {
        MenuRegistryImpl registry = mock(MenuRegistryImpl.class);
        when(registry.getAllMenuItemsImpl()).thenThrow(new IllegalStateException("Menu registry kaputt"));
        return new PageAccessGuardService(registry, eigenschaften(mode, true));
    }

    static PlaintextSecurityProperties eigenschaften(PageGuardMode mode, boolean enabled) {
        PlaintextSecurityProperties properties = new PlaintextSecurityProperties();
        properties.getPageGuard().setMode(mode);
        properties.getPageGuard().setEnabled(enabled);
        return properties;
    }
}

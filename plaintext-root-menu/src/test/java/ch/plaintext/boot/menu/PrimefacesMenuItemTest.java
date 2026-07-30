/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PrimefacesMenuItemTest {

    @Test
    void shouldSetValueFromMenuItemTitle() {
        MenuItemImpl item = createMenuItem("Dashboard", "/dashboard.xhtml", "pi pi-home");

        PrimefacesMenuItem menuItem = new PrimefacesMenuItem(item);

        assertEquals("Dashboard", menuItem.getValue());
    }

    @Test
    void shouldSetUrlFromMenuItemCommand() {
        MenuItemImpl item = createMenuItem("Dashboard", "/dashboard.xhtml", "pi pi-home");

        PrimefacesMenuItem menuItem = new PrimefacesMenuItem(item);

        assertEquals("/dashboard.xhtml", menuItem.getUrl());
    }

    @Test
    void shouldSetIconFromMenuItem() {
        MenuItemImpl item = createMenuItem("Dashboard", "/dashboard.xhtml", "pi pi-home");

        PrimefacesMenuItem menuItem = new PrimefacesMenuItem(item);

        assertEquals("pi pi-home", menuItem.getIcon());
    }

    @Test
    void shouldReturnValueDynamicallyFromMenuItem() {
        MenuItemImpl item = createMenuItem("Initial", "/initial.xhtml", "");
        PrimefacesMenuItem menuItem = new PrimefacesMenuItem(item);

        assertEquals("Initial", menuItem.getValue());

        // Change the title on the underlying item
        item.setTitle("Updated");
        assertEquals("Updated", menuItem.getValue());
    }

    @Test
    void shouldReturnTrueForIsRenderedWhenVisible() {
        MenuItemImpl item = createMenuItem("Visible", "/visible.xhtml", "");
        // No roles or visibility providers, so isOn() returns true
        PrimefacesMenuItem menuItem = new PrimefacesMenuItem(item);

        assertTrue(menuItem.isRendered());
    }

    @Test
    void shouldReturnFalseForIsRenderedWhenNotVisible() {
        MenuItemImpl item = createMenuItem("Hidden", "/hidden.xhtml", "");
        SecurityProvider securityProvider = role -> false;
        item.setSecurityProvider(securityProvider);
        item.setRoles(List.of("ADMIN"));

        PrimefacesMenuItem menuItem = new PrimefacesMenuItem(item);

        assertFalse(menuItem.isRendered());
    }

    @Test
    void shouldHandleEmptyUrl() {
        MenuItemImpl item = createMenuItem("NoUrl", "", "");

        PrimefacesMenuItem menuItem = new PrimefacesMenuItem(item);

        assertEquals("", menuItem.getUrl());
    }

    @Test
    void shouldHandleEmptyIcon() {
        MenuItemImpl item = createMenuItem("NoIcon", "/noicon.xhtml", "");

        PrimefacesMenuItem menuItem = new PrimefacesMenuItem(item);

        assertEquals("", menuItem.getIcon());
    }

    @Test
    void shouldWorkWithAbstractMenuItemDirectly() {
        AbstractMenuItem abstractItem = new AbstractMenuItem() {
            @Override
            public String getTitle() {
                return "AbstractTitle";
            }

            @Override
            public String getParent() {
                return "";
            }

            @Override
            public String getCommand() {
                return "/abstract.xhtml";
            }

            @Override
            public String getIc() {
                return "pi pi-star";
            }
        };

        PrimefacesMenuItem menuItem = new PrimefacesMenuItem(abstractItem);

        assertEquals("AbstractTitle", menuItem.getValue());
        assertEquals("/abstract.xhtml", menuItem.getUrl());
        assertEquals("pi pi-star", menuItem.getIcon());
        assertTrue(menuItem.isRendered());
    }

    @Test
    void shouldReflectIsOnChangeDynamically() {
        MenuItemImpl item = createMenuItem("Dynamic", "/dynamic.xhtml", "");

        PrimefacesMenuItem menuItem = new PrimefacesMenuItem(item);
        assertTrue(menuItem.isRendered());

        // Now make it invisible via roles
        SecurityProvider securityProvider = role -> false;
        item.setSecurityProvider(securityProvider);
        item.setRoles(List.of("RESTRICTED"));

        assertFalse(menuItem.isRendered());
    }

    private MenuItemImpl createMenuItem(String title, String command, String icon) {
        MenuItemImpl item = new MenuItemImpl();
        item.setTitle(title);
        item.setCommand(command);
        item.setIcon(icon);
        return item;
    }
}

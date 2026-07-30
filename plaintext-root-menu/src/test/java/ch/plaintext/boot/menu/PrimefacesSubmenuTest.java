/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PrimefacesSubmenuTest {

    @Test
    void shouldSetLabelFromMenuItemTitle() {
        MenuItemImpl item = createMenuItem("Administration", "", "pi pi-cog", "/admin.xhtml");

        PrimefacesSubmenu submenu = new PrimefacesSubmenu(item);

        assertEquals("Administration", submenu.getLabel());
    }

    @Test
    void shouldSetIconFromMenuItem() {
        MenuItemImpl item = createMenuItem("Settings", "", "pi pi-wrench", "/settings.xhtml");

        PrimefacesSubmenu submenu = new PrimefacesSubmenu(item);

        assertEquals("pi pi-wrench", submenu.getIcon());
    }

    @Test
    void shouldReturnLabelDynamicallyFromMenuItem() {
        MenuItemImpl item = createMenuItem("Initial", "", "", "");
        PrimefacesSubmenu submenu = new PrimefacesSubmenu(item);

        assertEquals("Initial", submenu.getLabel());

        // Change the title on the underlying item
        item.setTitle("Changed");
        assertEquals("Changed", submenu.getLabel());
    }

    @Test
    void shouldHaveEmptyElementsByDefault() {
        MenuItemImpl item = createMenuItem("Empty", "", "", "");

        PrimefacesSubmenu submenu = new PrimefacesSubmenu(item);

        assertNotNull(submenu.getElements());
        assertTrue(submenu.getElements().isEmpty());
    }

    @Test
    void shouldDelegateIsRenderedToIsOn() {
        MenuItemImpl item = createMenuItem("Visible", "", "", "");
        // No roles, no visibility provider -> isOn() returns true
        PrimefacesSubmenu submenu = new PrimefacesSubmenu(item);

        assertTrue(submenu.isRendered());
    }

    @Test
    void shouldReturnFalseWhenMenuItemIsNotVisible() {
        MenuItemImpl item = createMenuItem("Hidden", "", "", "");
        SecurityProvider securityProvider = role -> false;
        item.setSecurityProvider(securityProvider);
        item.setRoles(java.util.List.of("ADMIN"));

        PrimefacesSubmenu submenu = new PrimefacesSubmenu(item);

        assertFalse(submenu.isRendered());
    }

    @Test
    void shouldReturnMenuItemImplFromGetMenuItem() {
        MenuItemImpl item = createMenuItem("Test", "", "", "");

        PrimefacesSubmenu submenu = new PrimefacesSubmenu(item);

        assertNotNull(submenu.getMenuItem());
        assertSame(item, submenu.getMenuItem());
    }

    @Test
    void shouldReturnNullFromGetMenuItemWhenNotMenuItemImpl() {
        AbstractMenuItem abstractItem = new AbstractMenuItem() {
            @Override
            public String getTitle() {
                return "Abstract";
            }

            @Override
            public String getParent() {
                return "";
            }

            @Override
            public String getCommand() {
                return "";
            }
        };

        PrimefacesSubmenu submenu = new PrimefacesSubmenu(abstractItem);

        assertNull(submenu.getMenuItem());
    }

    @Test
    void shouldAllowAddingElements() {
        MenuItemImpl parentItem = createMenuItem("Parent", "", "", "");
        PrimefacesSubmenu submenu = new PrimefacesSubmenu(parentItem);

        MenuItemImpl childItem = createMenuItem("Child", "Parent", "", "/child.xhtml");
        PrimefacesMenuItem childMenuItem = new PrimefacesMenuItem(childItem);
        submenu.getElements().add(childMenuItem);

        assertEquals(1, submenu.getElements().size());
    }

    @Test
    void shouldHandleEmptyIcon() {
        MenuItemImpl item = createMenuItem("NoIcon", "", "", "/noicon.xhtml");

        PrimefacesSubmenu submenu = new PrimefacesSubmenu(item);

        assertEquals("", submenu.getIcon());
    }

    private MenuItemImpl createMenuItem(String title, String parent, String icon, String command) {
        MenuItemImpl item = new MenuItemImpl();
        item.setTitle(title);
        item.setParent(parent);
        item.setIcon(icon);
        item.setCommand(command);
        return item;
    }
}
